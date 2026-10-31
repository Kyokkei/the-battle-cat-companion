package com.projectnyanko.companion.ui.stage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import androidx.core.graphics.createBitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.projectnyanko.companion.domain.cat.CatScanHit
import com.projectnyanko.companion.domain.cat.CatScanResolver
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.BattleCatsApplication
import com.projectnyanko.companion.domain.stage.StageLocale
import com.projectnyanko.companion.domain.stage.OcrStageLine
import com.projectnyanko.companion.domain.stage.StageCandidate
import com.projectnyanko.companion.domain.stage.StageMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

/** Holds the user-approved screen stream and reads a frame only after the bubble is tapped. */
class StageCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ocrLock = Any()
    private var ocrEngine: StageOcrEngine? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    @Volatile
    private var captureRequested = false

    @Volatile
    private var captureInProgress = false

    /**
     * Timestamp of the newest frame this session has delivered, in the frame clock's own units.
     *
     * A mirroring surface that is re-attached can hand back the composition it was showing before it
     * was detached. Accepting that frame made the helper OCR a screen the player had already left —
     * it reported `Slumbering Cavern` and `Cozy Corpses` while `Sodden Bones` was on screen, and
     * garbage such as `cYanmaGentaYellow` when the stale composition had the card row elsewhere.
     * Comparing timestamps is clock-base independent and needs no knowledge of the device, so only a
     * frame newer than everything seen before the tap can reach OCR.
     */
    @Volatile
    private var newestFrameTimestamp = Long.MIN_VALUE

    /** Frames older than this were composed before the bubble was hidden, so they are not used. */
    @Volatile
    private var acceptFromTimestamp = Long.MIN_VALUE

    /** False until [hideBubbleAndAccept] runs; frames arriving in the probe window are only drained. */
    @Volatile
    private var acceptingFrames = false

    private var staleFrameCount = 0

    /** How many times this tap has re-captured because the frame held no stage name. */
    private var ocrRetries = 0

    private var captureAttempt = 0

    private var isUserStopping = false
    private var projectionActive = false
    private var locale = StageLocale.EN
    private var scanMode = ScanMode.STAGE
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        createNotificationChannel()
        captureThread = HandlerThread("stage-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        locale = intent?.getStringExtra(StageOverlayService.EXTRA_REGION)?.toLocale() ?: locale
        scanMode = ScanMode.fromName(intent?.getStringExtra(StageOverlayService.EXTRA_SCAN_MODE))
        try {
            when (intent?.action) {
                ACTION_ENABLE -> enableProjection(intent)
                ACTION_CAPTURE -> requestCapture()
                ACTION_SELF_TEST -> serviceScope.launch {
                    runSelfTest(intent.getStringExtra(EXTRA_TEST_FRAME))
                }
                ACTION_STOP -> {
                    isUserStopping = true
                    stopSelf()
                }
            }
        } catch (error: Exception) {
            StageOverlayService.showMessage(
                this,
                locale.name,
                error.message?.let { "Stage capture failed: $it" } ?: "Stage capture failed. Return to the app and retry.",
            )
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Swiping the task away must not end the session: the projection belongs to this service, not to
     * the activity. Logged because if a device still drops it, this line tells us whether the task
     * removal reached the service at all.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; keeping the screen-share session")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        projectionActive = false
        isProjectionActive = false
        captureRequested = false
        displayListener?.let { listener -> displayManager?.unregisterDisplayListener(listener) }
        displayListener = null
        releaseProjection()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        serviceScope.cancel()
        val engineToClose = ocrEngine
        ocrEngine = null
        if (engineToClose != null) {
            Thread({ synchronized(ocrLock) { engineToClose.close() } }, "stage-ocr-release").start()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enableProjection(intent: Intent?) {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.parcelableIntent(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            StageOverlayService.showMessage(this, locale.name, "Screen capture permission was not received. Tap the helper to try again.")
            stopSelf()
            return
        }

        startForegroundProjectionNotification()
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = runCatching { manager.getMediaProjection(resultCode, resultData) }
            .getOrElse {
                StageOverlayService.showMessage(this, locale.name, "Android could not start screen capture. Tap the helper to try again.")
                stopSelf()
                return
            }
        val activeProjection = projection ?: run {
            stopSelf()
            return
        }
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!isUserStopping) {
                    StageOverlayService.showMessage(this@StageCaptureService, locale.name, "Android ended screen sharing. Start the helper again to continue.")
                }
                stopSelf()
            }
        }.also { activeProjection.registerCallback(it, captureHandler) }
        val (width, height, density) = displaySize()
        captureWidth = width
        captureHeight = height
        captureDensity = density
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = activeProjection.createVirtualDisplay(
            "Battle Cats stage helper",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler,
        )
        if (virtualDisplay == null) {
            StageOverlayService.showMessage(this, locale.name, "Android could not start the screen session. Tap Start helper and approve it again.")
            stopSelf()
            return
        }
        virtualDisplay?.setSurface(null)
        projectionActive = true
        isProjectionActive = true
        isRunning = true
        registerDisplayListener()
        StageOverlayService.startBubble(this, locale)
    }

    /**
     * Writes what the last stage scan actually read.
     *
     * The logcat ring buffer on this phone is 256 KiB and mostly system chatter, so a scan's lines
     * are gone within seconds — which is exactly when someone asks "why did it say round three when
     * I am on round two". The cat reader already keeps a dump; the stage reader now does too.
     */
    private fun dumpStageScan(
        lines: List<com.projectnyanko.companion.domain.stage.OcrStageLine>,
        matches: com.projectnyanko.companion.domain.stage.StageMatchResult,
        frame: Bitmap,
        locale: StageLocale,
    ) {
        runCatching {
            // External app files, not cacheDir: a release build is not debuggable, so `run-as` cannot
            // read the internal cache — which made the first version of this dump unreadable on the
            // one build that actually ships.
            val base = getExternalFilesDir(null) ?: filesDir
            val dir = java.io.File(base, "stagescan").apply { mkdirs() }
            dir.listFiles()?.sortedBy { it.name }?.dropLast(CAT_SCAN_DUMPS)?.forEach { it.delete() }
            val stamp = "scan-" + System.currentTimeMillis()
            val body = buildString {
                appendLine("frame=${frame.width}x${frame.height}")
                appendLine("likely=" + (matches.likelySelected?.stage?.names(locale)?.firstOrNull() ?: "none"))
                appendLine(
                    "candidates=" + matches.candidates.joinToString(" | ") {
                        "${it.stage.names(locale).firstOrNull()}(%.2f)".format(java.util.Locale.ROOT, it.confidence)
                    },
                )
                appendLine("lines=${lines.size}")
                lines.forEach { line ->
                    appendLine(
                        "  [%.3f-%.3f x %.3f-%.3f] %s (%.2f)".format(
                            java.util.Locale.ROOT,
                            line.top, line.bottom, line.left, line.right, line.text, line.confidence,
                        ),
                    )
                }
            }
            java.io.File(dir, "$stamp.txt").writeText(body)
            // The frame goes with it. A dump says what OCR produced; only the picture says why —
            // and "the selected card's label never reads" is a question about pixels, not text.
            runCatching {
                java.io.FileOutputStream(java.io.File(dir, "$stamp-frame.png")).use { output ->
                    frame.compress(Bitmap.CompressFormat.PNG, 90, output)
                }
            }
        }
    }

    /** Keeps the last unreadable frame so a failure can be inspected instead of guessed at. */
    private fun dumpFrame(bitmap: Bitmap, index: Int) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            java.io.File(dir, "unread-frame-$index.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }

    private fun requestCapture() {
        if (!projectionActive || projection == null || imageReader == null || virtualDisplay == null) {
            Log.w(TAG, "capture requested without a live projection session")
            StageOverlayService.showMessage(
                this,
                locale.name,
                "The helper session ended. Return to the app and press Start helper to approve a new session.",
            )
            return
        }
        // A tap always wins over a queued retry. The stage reader retries an unmatched frame a few
        // times, and every one of those windows used to swallow the tap, so pressing the paw in cat
        // mode did nothing at all.
        captureHandler?.removeCallbacksAndMessages(null)
        if (captureInProgress) {
            Log.i(TAG, "capture in progress; tap arrives after this frame is read")
            return
        }
        Log.i(TAG, "capture requested")
        captureAttempt = 0
        staleFrameCount = 0
        ocrRetries = 0
        acceptingFrames = false
        // The bubble is deliberately left visible here. See [hideBubbleAndAccept].
        captureHandler?.postDelayed({ attachCaptureSurface() }, CAPTURE_SETTLE_DELAY_MS)
    }

    /**
     * Attaches the reader surface, then hides the bubble only once the surface is live.
     *
     * Order matters and was the cause of "the first four taps do nothing". A mirroring surface that
     * is attached to a display whose content is already static can only hand back the composition it
     * last rendered, so hiding the bubble *before* attaching composed the change while nothing was
     * listening and the surface had nothing newer to replay. Attaching first and hiding afterwards
     * makes the hide a real composition change on a live surface, so its frame is both current and
     * free of the overlay.
     *
     * A failed attempt is retried: the bubble is shown again first, so the next hide is another real
     * composition change rather than a no-op.
     */
    private fun attachCaptureSurface() {
        val reader = imageReader
        val display = virtualDisplay
        if (!projectionActive || projection == null || reader == null || display == null) {
            abortCapture("The helper session ended. Return to the app and press Start helper to approve a new session.")
            return
        }
        if (captureInProgress) return
        if (captureAttempt >= MAX_CAPTURE_ATTEMPTS) {
            abortCapture(
                if (staleFrameCount > 0) {
                    "Only a stale frame arrived. Return to the stage screen and tap the paw again."
                } else {
                    "No frame arrived. Return to the game and tap the paw again."
                },
            )
            return
        }
        captureAttempt += 1
        val attempt = captureAttempt
        Log.i(TAG, "waiting for a frame (attempt $attempt/$MAX_CAPTURE_ATTEMPTS)")
        if (attempt > 1) StageOverlayService.startBubble(this, locale)
        runCatching {
            while (true) {
                val stale = reader.acquireLatestImage() ?: break
                newestFrameTimestamp = maxOf(newestFrameTimestamp, stale.timestamp)
                stale.close()
            }
            acceptingFrames = false
            captureRequested = true
            reader.setOnImageAvailableListener(::onImageAvailable, captureHandler)
            display.setSurface(null)
            display.setSurface(reader.surface)
        }.onFailure { error ->
            Log.w(TAG, "attaching the capture surface failed", error)
            captureRequested = false
            reader.setOnImageAvailableListener(null, null)
            runCatching { display.setSurface(null) }
            StageOverlayService.showMessage(this, locale.name, error.message?.let { "Could not capture a still: $it" } ?: "Could not capture a still. Tap again.")
        }
        captureHandler?.postDelayed({ hideBubbleAndAccept(attempt) }, CAPTURE_PROBE_DELAY_MS)
        captureHandler?.postDelayed({
            if (attempt == captureAttempt && captureRequested && !captureInProgress) {
                Log.w(TAG, "no frame on attempt $attempt; retrying")
                captureRequested = false
                reader.setOnImageAvailableListener(null, null)
                runCatching { display.setSurface(null) }
                // Some devices (One UI especially) never begin mirroring into a surface that was
                // attached at creation: the virtual display sits idle until the compositor is made
                // to re-evaluate it. That is exactly why turning the screen off and on "fixes" a
                // scan — it forces that re-evaluation. Rebuilding the display does the same thing
                // on purpose, without asking the player to lock their phone.
                val rebuilt = rebuildCaptureDisplay()
                Log.i(TAG, "retry $attempt: display rebuilt = $rebuilt")
                captureHandler?.postDelayed({ attachCaptureSurface() }, CAPTURE_RETRY_DELAY_MS)
            }
        }, CAPTURE_ATTEMPT_TIMEOUT_MS)
    }

    /**
     * Tears the mirroring display down and builds a fresh one.
     *
     * A `setSurface(null)` / `setSurface(surface)` cycle is not enough on every OEM: the display
     * keeps the composition it had and simply never produces another frame, so every retry waits
     * on a display that has nothing to say. Creating a new VirtualDisplay restarts the mirroring
     * pipeline, which is what the screen-off/on workaround was accidentally doing.
     *
     * The MediaProjection itself is reused — it survives, and re-creating it would need the user's
     * approval again.
     */
    private fun rebuildCaptureDisplay(): Boolean {
        val active = projection ?: return false
        val reader = imageReader ?: return false
        val previous = virtualDisplay
        // Android 14+ allows one virtual display per projection, so the old one has to go first.
        // It is kept in a local so a failed rebuild can put the session back the way it was instead
        // of leaving the player with a dead helper.
        runCatching { previous?.setSurface(null) }
        runCatching { previous?.release() }
        val display = runCatching {
            active.createVirtualDisplay(
                "Battle Cats stage helper",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            )
        }.getOrNull()
        if (display == null) {
            Log.w(TAG, "rebuilding the capture display failed; keeping the released one so the session survives")
            virtualDisplay = previous
            return false
        }
        virtualDisplay = display
        return true
    }

    /**
     * Hides the bubble now that the mirroring surface is live, and starts accepting frames newer
     * than everything seen so far. The snapshot is taken before the hide so a replayed composition
     * delivered afterwards still compares as stale.
     */
    private fun hideBubbleAndAccept(attempt: Int) {
        if (attempt != captureAttempt || captureInProgress || !captureRequested) return
        acceptFromTimestamp = newestFrameTimestamp
        acceptingFrames = true
        StageOverlayService.hideBubble(this, locale)
        Log.i(TAG, "bubble hidden; accepting frames newer than $acceptFromTimestamp")
    }

    private fun abortCapture(message: String) {
        captureRequested = false
        acceptingFrames = false
        captureAttempt = 0
        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { virtualDisplay?.setSurface(null) }
        // The bubble is hidden while a capture is in flight. Restoring it here means a failed scan
        // can never leave the player with no bubble and no card — which reads as "it vanished".
        StageOverlayService.startBubble(this, locale)
        StageOverlayService.showMessage(this, locale.name, message)
    }

    private fun onImageAvailable(reader: ImageReader) {
        try {
            processImageAvailable(reader)
        } catch (error: Exception) {
            captureRequested = false
            captureInProgress = false
            StageOverlayService.showMessage(
                this,
                locale.name,
                error.message?.let { "Could not process the captured frame: $it" } ?: "Could not process the captured frame.",
            )
        }
    }

    private fun processImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        if (!captureRequested || captureInProgress) {
            image.close()
            return
        }
        if (!acceptingFrames) {
            // Probe window: the surface has just been attached and may still be replaying. Keep the
            // newest timestamp so the frame the hide produces is the first one accepted.
            newestFrameTimestamp = maxOf(newestFrameTimestamp, image.timestamp)
            image.close()
            return
        }
        if (image.timestamp <= acceptFromTimestamp) {
            // A re-attached mirroring surface replays its previous composition. Drop it and keep
            // waiting instead of reading a screen the player is no longer looking at.
            staleFrameCount += 1
            Log.w(TAG, "discarding stale frame (timestamp ${image.timestamp} <= ${acceptFromTimestamp})")
            newestFrameTimestamp = maxOf(newestFrameTimestamp, image.timestamp)
            image.close()
            return
        }
        newestFrameTimestamp = maxOf(newestFrameTimestamp, image.timestamp)
        captureRequested = false
        captureAttempt = 0
        captureInProgress = true
        Log.i(TAG, "frame received (${image.width}x${image.height}, timestamp ${image.timestamp})")
        val bitmap = try {
            image.toBitmap()
        } catch (error: OutOfMemoryError) {
            captureInProgress = false
            StageOverlayService.showMessage(this, locale.name, "Not enough memory to read that screen. Close other apps and retry.")
            return
        } catch (error: Exception) {
            captureInProgress = false
            StageOverlayService.showMessage(
                this,
                locale.name,
                error.message?.let { "Could not read that frame: $it" } ?: "Could not read that frame. Tap the paw to retry.",
            )
            return
        } finally {
            image.close()
        }
        virtualDisplay?.setSurface(null)
        reader.setOnImageAvailableListener(null, null)
        serviceScope.launch {
            try {
                if (scanMode == ScanMode.CAT) {
                    // Cat mode never consults the stage matcher: on a cat screen a stray number is
                    // enough to "resolve" a stage and swallow the frame.
                    val catHit = resolveCat(bitmap, locale)
                    if (catHit != null) {
                        startService(
                            Intent(this@StageCaptureService, StageOverlayService::class.java)
                                .setAction(StageOverlayService.ACTION_SHOW_CAT)
                                .putExtra(StageOverlayService.EXTRA_REGION, locale.name)
                                .putExtra(StageOverlayService.EXTRA_CAT_UNIT_ID, catHit.unitId)
                                .putExtra(StageOverlayService.EXTRA_CAT_FORM_INDEX, catHit.formIndex)
                                .putExtra(StageOverlayService.EXTRA_CAT_LEVEL, catHit.level ?: -1)
                                .putExtra(StageOverlayService.EXTRA_CAT_PLUS, catHit.plusLevel ?: -1)
                                .putExtra(StageOverlayService.EXTRA_CAT_NAME, catHit.displayName)
                                .putExtra(StageOverlayService.EXTRA_CAT_CONFIDENCE, catHit.nameConfidence),
                        )
                    } else {
                        StageOverlayService.showMessage(
                            this@StageCaptureService,
                            locale.name,
                            "No cat name on this screen. Tap again if the frame was still, or hold the paw to switch back to stage mode.",
                            title = "Cat scan",
                        )
                    }
                    return@launch
                }
                val catalog = withContext(Dispatchers.IO) {
                    (application as BattleCatsApplication).stageCatalogRepository.getCatalog()
                }
                val lines = withTimeout(OCR_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        synchronized(ocrLock) {
                            val modelStore = (application as BattleCatsApplication).stageOcrModelStore
                            val engine = ocrEngine ?: StageOcrEngine(modelStore).also { ocrEngine = it }
                            engine.recognize(bitmap, locale)
                        }
                    }
                }
                val matches = StageMatcher.match(catalog, lines, locale)
                dumpStageScan(lines, matches, bitmap, locale)
                val selectedEnergy = energyUnderSelectedCard(lines, matches.likelySelected)
                Log.i(
                    TAG,
                    "OCR read (${bitmap.width}x${bitmap.height}, try ${ocrRetries + 1}): " +
                        lines.map { it.text }.take(12).joinToString(" | "),
                )
                if (matches.candidates.isEmpty() && ocrRetries < MAX_OCR_RETRIES) {
                    // The engine reads a real stage-select screenshot perfectly, so an empty result
                    // means this frame is not the screen: the mirroring surface replayed a
                    // composition from before the cards were drawn. Capture again rather than
                    // telling the player nothing was found.
                    ocrRetries += 1
                    dumpFrame(bitmap, ocrRetries)
                    Log.w(TAG, "no stage name in this frame; capturing again (retry $ocrRetries)")
                    captureAttempt = 0
                    captureHandler?.postDelayed({ attachCaptureSurface() }, OCR_RETRY_DELAY_MS)
                    return@launch
                }
                if (matches.candidates.isEmpty()) dumpFrame(bitmap, ocrRetries)
                // Detection reads every label on the screen, so the diagnostic line shows the reads
                // that actually resolved to a stage. When nothing resolves it falls back to the raw
                // text, which is exactly the case where that text is needed.
                val reportedTexts = matches.candidates.map { it.matchedText }.distinct()
                    .ifEmpty { lines.map { it.text } }
                    .take(8)
                    .toTypedArray()
                val resultIntent = Intent(this@StageCaptureService, StageOverlayService::class.java)
                    .setAction(StageOverlayService.ACTION_SHOW_RESULT)
                    .putExtra(StageOverlayService.EXTRA_REGION, locale.name)
                    .putExtra(StageOverlayService.EXTRA_STAGE_IDS, matches.candidates.map { it.stage.id }.toLongArray())
                    .putExtra(StageOverlayService.EXTRA_LIKELY_STAGE_ID, matches.likelySelected?.stage?.id ?: -1L)
                    .putExtra(StageOverlayService.EXTRA_SELECTED_ENERGY, selectedEnergy ?: -1)
                    .putExtra(StageOverlayService.EXTRA_OCR_TEXTS, matches.candidates.map { it.matchedText }.toTypedArray())
                    .putExtra(StageOverlayService.EXTRA_RECOGNIZED_TEXT, reportedTexts)
                startService(resultIntent)
            } catch (error: OutOfMemoryError) {
                StageOverlayService.showMessage(this@StageCaptureService, locale.name, "OCR ran out of memory. Close other apps and try again.")
            } catch (error: LinkageError) {
                StageOverlayService.showMessage(
                    this@StageCaptureService,
                    locale.name,
                    "ONNX Runtime could not load on this device: ${error.message ?: error.javaClass.simpleName}",
                )
            } catch (error: Exception) {
                StageOverlayService.showMessage(
                    this@StageCaptureService,
                    locale.name,
                    error.message?.let { "Could not scan this screen: $it" } ?: "Could not scan this screen. Tap the helper to retry.",
                )
            } finally {
                bitmap.recycle()
                captureInProgress = false
            }
        }
    }

    /**
     * Reads a cat screen: the name bar and the level printed beside it.
     *
     * Returns null when the frame is neither a stage nor a cat screen, so the caller's retry path
     * still handles a replayed composition.
     */
    private suspend fun resolveCat(bitmap: Bitmap, locale: StageLocale, quiet: Boolean = false): CatScanHit? =
        withContext(Dispatchers.IO) {
            // The game is not always the whole display: in a floating or split window it is a
            // letterboxed band in the middle, and every box on screen comes out vertically squashed.
            // All the level geometry is relative to the name box, so a squashed frame breaks it —
            // crop to the game's own pixels first.
            val frame = gameFrame(bitmap)
            val input = ocrInput(frame)
            try {
                val app = application as BattleCatsApplication
                // Cat mode returns before the stage path runs, so this is the only place that would
                // ever build the engine for a cat-only session. Without it the scan silently failed
                // with "no cat name" while the stage reader had just read the same screen fine.
                val engine = ocrEngine ?: StageOcrEngine(app.stageOcrModelStore).also { ocrEngine = it }
                val region = if (locale == StageLocale.JP) GameRegion.JP else GameRegion.EN
                val index = app.catScanCatalog.index(region)
                val startedAt = System.currentTimeMillis()
                val all = withTimeout(CAT_SCAN_TIMEOUT_MS) {
                    engine.recognizeAllLines(input, locale)
                }
                Log.i(
                    TAG,
                    "cat scan read ${all.size} lines in ${System.currentTimeMillis() - startedAt} ms: " +
                        all.take(16).joinToString(" | ") { "${it.text}(${"%.2f".format(it.bottom - it.top)})" },
                )
                CatScanResolver.resolve(all, index)?.let { hit ->
                    val trace = ScanTrace()
                    // Refine reads the uncapped frame: the capped copy is for the cheap full-screen
                    // pass, but the plus level is the smallest thing on the screen and deserves
                    // every pixel the device actually captured.
                    val refined = refineLevel(frame, hit, trace)
                    if (!quiet) dumpCatScan(all, refined, trace, frame, bitmap, frame !== bitmap)
                    refined
                }.also { hit ->
                    Log.i(TAG, "cat scan hit: ${hit?.displayName ?: "none"} level=${hit?.level} plus=${hit?.plusLevel}")
                }
            } catch (error: Exception) {
                Log.w(TAG, "cat scan failed: ${error.message}")
                null
            } finally {
                if (input !== frame) input.recycle()
                if (frame !== bitmap) frame.recycle()
            }
        }

    /**
     * Caps the frame handed to the recognizer.
     *
     * The detector already works at a 1280 long side, but the recognizer runs one crop per text box,
     * so cost scales with area: a fullscreen 2400x1080 capture took ~6 s per scan while a 1072x480
     * game window took ~1 s. Nothing readable at 2400 px is lost at 1600.
     */
    private fun ocrInput(frame: Bitmap): Bitmap {
        val longSide = maxOf(frame.width, frame.height)
        if (longSide <= MAX_OCR_LONG_SIDE) return frame
        val ratio = MAX_OCR_LONG_SIDE / longSide.toFloat()
        return runCatching {
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * ratio).toInt().coerceAtLeast(1),
                (frame.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrDefault(frame)
    }

    /**
     * Crops the capture to the pixels the game actually drew.
     *
     * A phone in portrait with a landscape game, a floating window, or a stale projection buffer all
     * produce a frame where the game sits in a band surrounded by black. Normalised coordinates are
     * taken against the bitmap, so that band squashes every box and every ratio derived from a name
     * box stops meaning anything.
     */
    private fun gameFrame(bitmap: Bitmap): Bitmap {
        val bounds = contentBounds(bitmap) ?: return bitmap
        if (bounds.width() >= bitmap.width * 0.97f && bounds.height() >= bitmap.height * 0.97f) return bitmap
        return runCatching {
            Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        }.getOrDefault(bitmap)
    }

    /** Bounding box of every pixel that is not near-black, sampled every [BOUNDS_STEP] px. */
    private fun contentBounds(bitmap: Bitmap): android.graphics.Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r + g + b > 60) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                x += BOUNDS_STEP
            }
            y += BOUNDS_STEP
        }
        if (right <= left || bottom <= top) return null
        // Sampling lands on a grid, so the real edge is up to one step further out.
        return android.graphics.Rect(
            (left - BOUNDS_STEP).coerceAtLeast(0),
            (top - BOUNDS_STEP).coerceAtLeast(0),
            (right + BOUNDS_STEP).coerceAtMost(bitmap.width),
            (bottom + BOUNDS_STEP).coerceAtMost(bitmap.height),
        )
    }

    /**
     * Re-reads the level block from a crop instead of trusting the full-screen digits.
     *
     * The block is always in the name's own column, a handful of name-heights below it: under the
     * portrait on the Upgrade card, under the panel name on Organize Cats. Cropping by **name
     * heights** instead of screen fractions is what makes this work on both screens and on any
     * device — the name is the only box whose size tracks the UI scale.
     */
    private fun refineLevel(bitmap: Bitmap, hit: CatScanHit, trace: ScanTrace): CatScanHit {
        val name = hit.nameBounds ?: return hit
        val engine = ocrEngine ?: return hit
        val nameHeight = (name.bottom - name.top).coerceAtLeast(0.004f)
        val centreX = (name.left + name.right) / 2f
        val left = ((centreX - CROP_HALF_WIDTH * nameHeight) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val right = ((centreX + CROP_HALF_WIDTH * nameHeight) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val top = ((name.bottom + CROP_TOP * nameHeight) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = ((name.bottom + CROP_BOTTOM * nameHeight) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        if (right - left < 16 || bottom - top < 12) return hit
        val crop = runCatching { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) }
            .getOrNull() ?: return hit
        trace.crop = crop
        return try {
            // Scale by what the crop needs, not a flat 4x: on a 2400 px frame the digits are already
            // 30 px tall and 4x would mean OCR-ing a 3400 px image for nothing.
            val scale = (REFINE_TARGET_WIDTH / crop.width).coerceIn(1f, 4f)
            val big = Bitmap.createScaledBitmap(
                crop,
                (crop.width * scale).toInt().coerceAtLeast(1),
                (crop.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            try {
                val lines = engine.recognizeAllLines(big, locale)
                trace.refineText = lines.joinToString(" | ") { "${it.text}@${"%.2f".format(it.left)}" }
                val (level, plus) = CatScanResolver.readLevelBlock(lines) ?: (null to null)
                Log.i(TAG, "level block read: \"${trace.refineText}\" -> level=$level plus=$plus")
                hit.copy(
                    level = level ?: hit.level,
                    plusLevel = plus ?: hit.plusLevel,
                )
            } finally {
                if (big !== crop) big.recycle()
            }
        } catch (error: Exception) {
            Log.w(TAG, "level refine failed: ${error.message}")
            hit
        }
    }

    /** What the second pass saw, so a failed scan can be read back off the device. */
    private class ScanTrace {
        var refineText: String? = null
        var crop: Bitmap? = null
    }

    /**
     * Runs the real scan pipeline over the last captured frame, rescaled and letterboxed the way
     * other devices would deliver it.
     *
     * "It works on my phone" is not a test. The source frame is the last one the user actually
     * scanned, so this exercises the same OCR, the same crop and the same geometry on 16:9, 19.5:9,
     * 20:9, 4:3, tablet and portrait-window shapes. Any variant that disagrees with the native read
     * is a device the feature would break on, and the answer lands in `cache/selftest.txt`.
     */
    private suspend fun runSelfTest(requestedFrame: String? = null) {
        val dir = java.io.File(cacheDir, "catscan")
        val source = requestedFrame?.let { java.io.File(dir, it) }?.takeIf { it.isFile }
            ?: dir.listFiles()?.filter { it.name.endsWith("-frame.jpg") }?.maxByOrNull { it.name }
        if (source == null) return
        val base = android.graphics.BitmapFactory.decodeFile(source.absolutePath) ?: return
        val native = resolveCat(base, locale, quiet = true)
        val report = StringBuilder()
        report.appendLine("source=${source.name} ${base.width}x${base.height}")
        report.appendLine("native=${native?.displayName} level=${native?.level} plus=${native?.plusLevel}")

        for ((label, width, height) in SELF_TEST_SHAPES) {
            val variant = letterbox(base, width, height)
            val started = System.currentTimeMillis()
            val hit = resolveCat(variant, locale, quiet = true)
            val ms = System.currentTimeMillis() - started
            val ok = hit?.unitId == native?.unitId && hit?.level == native?.level && hit?.plusLevel == native?.plusLevel
            report.appendLine(
                "%-14s %4dx%-4d %s  name=%s level=%s plus=%s  %dms".format(
                    java.util.Locale.ROOT, label, width, height, if (ok) "OK  " else "FAIL",
                    hit?.displayName, hit?.level, hit?.plusLevel, ms,
                ),
            )
            if (variant !== base) variant.recycle()
        }
        java.io.File(cacheDir, "selftest.txt").writeText(report.toString())
        Log.i(TAG, "self test done:\n$report")
        base.recycle()
    }

    /** Scales to fit, centred on black — how a smaller or differently shaped display sees the game. */
    private fun letterbox(source: Bitmap, width: Int, height: Int): Bitmap {
        val canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val ratio = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledW = (source.width * ratio).toInt()
        val scaledH = (source.height * ratio).toInt()
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
        android.graphics.Canvas(canvas).drawBitmap(
            scaled,
            ((width - scaledW) / 2f),
            ((height - scaledH) / 2f),
            null,
        )
        if (scaled !== source) scaled.recycle()
        return canvas
    }

    /**
     * Writes the last few cat scans to the cache directory.
     *
     * The logcat ring buffer is only 256 KB and rotates within minutes on this device, so a failed
     * scan was unreadable by the time anyone looked. Each scan keeps its text, the frame the OCR saw
     * and the level crop it re-read, so a wrong level can be diagnosed without a second attempt.
     */
    private fun dumpCatScan(
        lines: List<com.projectnyanko.companion.domain.stage.OcrStageLine>,
        hit: CatScanHit?,
        trace: ScanTrace,
        frame: Bitmap,
        fullFrame: Bitmap,
        cropped: Boolean,
    ) {
        runCatching {
            val dir = java.io.File(cacheDir, "catscan").apply { mkdirs() }
            // Keep the last few scans; a ring is enough and the cache stays small.
            val existing = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            if (existing.size > CAT_SCAN_DUMPS * 3) existing.take(existing.size - CAT_SCAN_DUMPS * 3).forEach { it.delete() }
            val stamp = "scan-" + System.currentTimeMillis()
            val body = buildString {
                appendLine("name=" + (hit?.displayName ?: "none") + " level=" + hit?.level + " plus=" + hit?.plusLevel)
                appendLine("refine=\"" + (trace.refineText ?: "-") + "\"")
                appendLine("frame=" + frame.width + "x" + frame.height + " cropped=" + cropped +
                    " full=" + fullFrame.width + "x" + fullFrame.height)
                appendLine("lines=" + lines.size)
                lines.forEach { line ->
                    appendLine(
                        "  [%.3f-%.3f x %.3f-%.3f] %s (%.2f)".format(
                            java.util.Locale.ROOT,
                            line.top, line.bottom, line.left, line.right,
                            line.text, line.confidence,
                        ),
                    )
                }
            }
            java.io.File(dir, "$stamp.txt").writeText(body)
            // The frame goes with it. A dump says what OCR produced; only the picture says why —
            // and "the selected card's label never reads" is a question about pixels, not text.
            runCatching {
                java.io.FileOutputStream(java.io.File(dir, "$stamp-frame.png")).use { output ->
                    frame.compress(Bitmap.CompressFormat.PNG, 90, output)
                }
            }
            runCatching {
                // JPEG for the frame: it is only ever looked at, and a full-screen PNG costs ~250 ms
                // of the tap-to-card latency on a 2400x1080 capture.
                java.io.FileOutputStream(java.io.File(dir, "$stamp-frame.jpg")).use {
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
            }
            runCatching {
                trace.crop?.let { crop ->
                    java.io.FileOutputStream(java.io.File(dir, "$stamp-crop.png")).use {
                        crop.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
        trace.crop?.recycle()
        trace.crop = null
    }

    /**
     * Reads the energy the game prints under the selected card.
     *
     * One name can map to several records — `Crimson Star` has six — and the player has no way to tell
     * them apart from the stage list. The game does show one fact about the centred stage: its energy.
     * Reading it lets the overlay drop every candidate whose cost disagrees instead of asking the
     * player to compare numbers they cannot see.
     */
    private fun energyUnderSelectedCard(lines: List<OcrStageLine>, selected: StageCandidate?): Int? {
        val anchor = selected ?: return null
        val energyLabel = Regex("(?i)energy")
        val number = Regex("-?\\d+")
        val below = lines.filter { line -> line.top >= anchor.bottom - ENERGY_ROW_TOLERANCE }
        val labelLine = below
            .filter { energyLabel.containsMatchIn(it.text) }
            .filter { line -> minOf(line.right, anchor.right) - maxOf(line.left, anchor.left) > 0f }
            .minByOrNull { it.top } ?: return null
        number.find(labelLine.text)?.value?.toIntOrNull()?.let { return abs(it) }
        // The detector often splits `Energy` and `-250` into two boxes on the same row.
        return below
            .filter { line -> abs(line.top - labelLine.top) < ENERGY_ROW_TOLERANCE }
            .filter { line -> line.left >= labelLine.right - ENERGY_ROW_TOLERANCE }
            .minByOrNull { it.left }
            ?.text
            ?.let { number.find(it)?.value?.toIntOrNull() }
            ?.let { abs(it) }
    }

    private fun releaseProjection() {
        projectionCallback?.let { callback -> projection?.unregisterCallback(callback) }
        projectionCallback = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        projectionActive = false
        isProjectionActive = false
        isRunning = false
    }

    private fun registerDisplayListener() {
        val manager = displayManager ?: return
        if (displayListener != null) return
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) captureHandler?.post(::resizeCaptureDisplay)
            }
        }.also { manager.registerDisplayListener(it, captureHandler) }
    }

    private fun resizeCaptureDisplay() {
        if (!projectionActive || captureRequested || captureInProgress) return
        val display = virtualDisplay ?: return
        if (projection == null) return
        val currentReader = imageReader ?: return
        val (width, height, density) = displaySize()
        if (width == captureWidth && height == captureHeight && density == captureDensity) return
        val resizedReader = runCatching { ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2) }
            .getOrElse {
                StageOverlayService.showMessage(this, locale.name, "Could not resize the capture surface after screen rotation.")
                return
            }
        try {
            display.setSurface(null)
            display.resize(width, height, density)
            imageReader = resizedReader
            captureWidth = width
            captureHeight = height
            captureDensity = density
            currentReader.setOnImageAvailableListener(null, null)
            currentReader.close()
        } catch (error: Exception) {
            resizedReader.close()
            StageOverlayService.showMessage(
                this,
                locale.name,
                error.message?.let { "Could not resize the capture session: $it" } ?: "Could not resize the capture session.",
            )
        }
    }

    private fun displaySize(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private fun startForegroundProjectionNotification() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Stage helper screen sharing is active")
            .setContentText("Only paw taps read a single frame. Nothing is saved; stop here to end the session.")
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop sharing",
                    android.app.PendingIntent.getService(
                        this,
                        NOTIFICATION_ID,
                        Intent(this, StageCaptureService::class.java).setAction(ACTION_STOP),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stage scan screen sharing", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun String.toLocale() = if (equals("JP", ignoreCase = true)) StageLocale.JP else StageLocale.EN

    private fun Intent.parcelableIntent(key: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

    companion object {
        /** A cat screen is a single frame; anything slower than this means the engine is stuck. */
        private const val CAT_SCAN_TIMEOUT_MS = 15_000L

        /**
         * The level block crop, in multiples of the name's height.
         *
         * The name box is the only thing on screen whose size follows the UI scale, so cropping by
         * it lands on the block on a 16:9 phone, a 20:9 phone and a tablet alike — and on both the
         * Upgrade card and the Organize Cats panel, where the block sits at a different absolute
         * place but the same distance from the name.
         *
         * 3.0, not 2.4: the block hugs the right edge of the tile, and at 2.4 the `+7` on Cyberpunk
         * Cat ended up touching the crop's border, where the detector drops it — the card read a
         * level 34 with no plus. The neighbour's block that this now catches is thrown out by the
         * column check in `readLevelBlock`.
         */
        private const val CROP_HALF_WIDTH = 3.0f
        private const val CROP_TOP = 0.3f
        private const val CROP_BOTTOM = 9.0f

        /** Refine crops are scaled up to roughly this width before OCR, within [1, 4]x. */
        private const val REFINE_TARGET_WIDTH = 1400f

        /** How many past scans to keep in the cache for diagnosis. */
        private const val CAT_SCAN_DUMPS = 8

        /** Sampling stride when hunting for the game's own pixels inside a letterboxed capture. */
        private const val BOUNDS_STEP = 8

        /** Device shapes the self test replays the last real frame through. */
        private val SELF_TEST_SHAPES = listOf(
            Triple("16:9 phone", 1920, 1080),
            Triple("19.5:9", 2340, 1080),
            Triple("20:9", 2400, 1080),
            Triple("4:3 tablet", 1024, 768),
            Triple("16:10 tab", 1280, 800),
            Triple("portrait", 1080, 2400),
            Triple("720p", 1280, 720),
            Triple("small", 960, 540),
        )

        const val ACTION_SELF_TEST = "com.projectnyanko.companion.stage.SELF_TEST"
        const val EXTRA_TEST_FRAME = "test_frame"

        /**
         * Longest side handed to the recognizer.
         *
         * The recognizer runs one crop per text box and each crop is upscaled from the input, so
         * cost tracks the input width: a fullscreen 2400 px capture cost ~6 s per scan, while the
         * same screen at 1280 costs ~1 s. 1280 is also what the user's own game window reads at
         * (1072 px), so nothing that reads today stops reading.
         */
        private const val MAX_OCR_LONG_SIDE = 1280
        const val ACTION_ENABLE = "com.projectnyanko.companion.stage.ENABLE_CAPTURE"
        const val ACTION_CAPTURE = "com.projectnyanko.companion.stage.CAPTURE_FRAME"
        const val ACTION_STOP = "com.projectnyanko.companion.stage.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        private const val CHANNEL_ID = "stage_capture"
        private const val NOTIFICATION_ID = 4102
        private const val TAG = "StageCapture"
        private const val CAPTURE_SETTLE_DELAY_MS = 250L
        private const val CAPTURE_PROBE_DELAY_MS = 150L
        private const val OCR_TIMEOUT_MS = 30_000L
        private const val CAPTURE_ATTEMPT_TIMEOUT_MS = 1_500L
        private const val CAPTURE_RETRY_DELAY_MS = 350L
        private const val MAX_CAPTURE_ATTEMPTS = 3

        /**
         * A frame that contains no stage name at all is a bad frame, not a bad scan: re-capture
         * instead of reporting "no match". Two retries cost well under a second each.
         */
        private const val MAX_OCR_RETRIES = 2
        private const val OCR_RETRY_DELAY_MS = 250L

        /** Two labels on the same row, or a label just under the card, in normalised frame units. */
        private const val ENERGY_ROW_TOLERANCE = 0.02f

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isProjectionActive: Boolean = false
            private set

        fun requestConsent(context: Context, locale: StageLocale) {
            if (isProjectionActive) return
            launchConsent(context, locale)
        }

        private fun launchConsent(context: Context, locale: StageLocale) {
            val consent = Intent(context, StageCapturePermissionActivity::class.java)
                .putExtra(StageOverlayService.EXTRA_REGION, locale.name)
            if (context !is android.app.Activity) {
                consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            context.startActivity(consent)
        }

        fun enable(context: Context, resultCode: Int, data: Intent, locale: StageLocale) {
            val intent = Intent(context, StageCaptureService::class.java)
                .setAction(ACTION_ENABLE)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(StageOverlayService.EXTRA_REGION, locale.name)
            startCaptureService(context, intent)
        }

        fun requestCapture(context: Context, locale: StageLocale, mode: ScanMode = ScanMode.STAGE) {
            val intent = Intent(context, StageCaptureService::class.java)
                .setAction(ACTION_CAPTURE)
                .putExtra(StageOverlayService.EXTRA_REGION, locale.name)
                .putExtra(StageOverlayService.EXTRA_SCAN_MODE, mode.name)
            if (isRunning && isProjectionActive) startCaptureService(context, intent)
            else requestConsent(context, locale)
        }

        fun stop(context: Context) {
            if (isRunning) {
                context.startService(Intent(context, StageCaptureService::class.java).setAction(ACTION_STOP))
            } else {
                context.stopService(Intent(context, StageCaptureService::class.java))
            }
            context.stopService(Intent(context, StageOverlayService::class.java))
        }

        private fun startCaptureService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
