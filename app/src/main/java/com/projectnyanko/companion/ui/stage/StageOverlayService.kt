package com.projectnyanko.companion.ui.stage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.projectnyanko.companion.domain.cat.CatLevelCurve
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.ui.seed.formLabel
import androidx.core.view.isNotEmpty
import com.projectnyanko.companion.BattleCatsApplication
import com.projectnyanko.companion.R
import com.projectnyanko.companion.domain.stage.StageCandidateGroups
import com.projectnyanko.companion.domain.stage.StageCatalog
import com.projectnyanko.companion.domain.stage.StageLocale
import com.projectnyanko.companion.domain.stage.StageNameGroup
import com.projectnyanko.companion.domain.stage.StageRecord
import java.util.Locale
import kotlin.math.abs

/** Owns the draggable bubble and the temporary, user-dismissed result card. */
class StageOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var stopTarget: TextView? = null
    private var stopTargetParams: WindowManager.LayoutParams? = null
    private var resultView: View? = null
    private var resultParams: WindowManager.LayoutParams? = null
    private var selectedLocale = StageLocale.EN

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        selectedLocale = intent?.getStringExtra(EXTRA_REGION)?.toLocale() ?: StageLocale.EN
        startForegroundSafely()
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> showBubble()
            ACTION_HIDE_BUBBLE -> {
                bubble?.visibility = View.INVISIBLE
                resultView?.visibility = View.INVISIBLE
            }
            ACTION_SHOW_RESULT -> showCandidates(intent)
            ACTION_SHOW_CAT -> showCat(intent)
            ACTION_CLOSE_RESULT -> {
                removeResult()
                bubble?.visibility = View.VISIBLE
            }
            ACTION_STOP -> {
                StageCaptureService.stop(this)
                stopSelf()
            }
            ACTION_SHOW_MESSAGE -> showMessageCard(intent.getStringExtra(EXTRA_MESSAGE).orEmpty())
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; keeping the floating paw")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * The result window is sized once, from the metrics of the orientation it was created in. Swiping
     * out to a portrait launcher left that landscape-sized card on screen with its close button past
     * the right edge, so it could not be dismissed until something forced landscape again. Rotating
     * now closes the card; the bubble stays put.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (resultView != null) {
            Log.i(TAG, "configuration changed; dismissing the result card")
            closeResult()
        }
    }

    override fun onDestroy() {
        removeBubble()
        removeStopTarget()
        removeResult()
        super.onDestroy()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubble != null) {
            bubble?.visibility = View.VISIBLE
            return
        }
        // The bubble is the app icon, cropped to the same ink-ringed circle the rest of the UI uses,
        // so the floating paw is recognisable as this app at a glance.
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Battle Cats stage helper. Tap to scan."
            background = bubbleBackground()
            clipToOutline = true
        }
        val params = WindowManager.LayoutParams(
            dp(58),
            dp(58),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(180)
        }
        view.setOnClickListener {
            val region = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(PREFERENCE_REGION, selectedLocale.name)
                ?.toLocale() ?: selectedLocale
            StageCaptureService.requestCapture(this, region, currentScanMode())
        }
        applyScanModeToBubble()
        installDragAndTap(view, params)
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                bubble = view
                bubbleParams = params
            }
    }

    private fun installDragAndTap(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressFired = false
        // Holding the bubble switches what the paw reads. It has to be cancellable from MOVE so a
        // drag never toggles the mode by accident.
        val longPress = Runnable {
            if (!moved) {
                longPressFired = true
                toggleScanMode()
            }
        }
        view.setOnTouchListener { touched, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressFired = false
                    touched.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    moved = moved || abs(dx) > dp(6) || abs(dy) > dp(6)
                    if (moved) touched.removeCallbacks(longPress)
                    if (moved) {
                        if (stopTarget == null) showStopTarget()
                        params.x = (startX - dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(touched, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    touched.removeCallbacks(longPress)
                    if (longPressFired) {
                        longPressFired = false
                        removeStopTarget()
                        return@setOnTouchListener true
                    }
                    if (moved && isDroppedOnStopTarget(event.rawX, event.rawY)) {
                        StageCaptureService.stop(this)
                        stopSelf()
                    } else {
                        if (moved) snapBubbleToNearestEdge(event.rawX, params)
                        else touched.performClick()
                    }
                    removeStopTarget()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touched.removeCallbacks(longPress)
                    longPressFired = false
                    removeStopTarget()
                    true
                }
                else -> false
            }
        }
    }

    private fun showCandidates(intent: Intent) {
        lastCandidateIntent = Intent(intent)
        val ids = intent.getLongArrayExtra(EXTRA_STAGE_IDS) ?: longArrayOf()
        val likelyId = intent.getLongExtra(EXTRA_LIKELY_STAGE_ID, -1L)
        val shownEnergy = intent.getIntExtra(EXTRA_SELECTED_ENERGY, -1).toLong()
        val recognizedText = intent.getStringArrayExtra(EXTRA_RECOGNIZED_TEXT).orEmpty()
        val root = createCard()
        root.addView(headerRow("Stage scan", showBack = false) {})
        root.addView(label("Choose the stage that is selected in your game. The scan is not auto-confirmed.", 14f, INK_SOFT))
        if (recognizedText.isNotEmpty()) {
            root.addView(label("OCR read: ${recognizedText.take(8).joinToString(" · ")}", 12f, INK_SOFT))
        }
        if (ids.isEmpty()) {
            root.addView(label("No close stage-name match found. Try again with the selected stage name visible.", 15f, ACCENT_DEEP))
            showResultRoot(root)
            return
        }
        val catalog = (application as BattleCatsApplication).stageCatalogRepository.getCatalog()
        val groups = StageCandidateGroups.group(
            ids.toList().mapNotNull { id -> catalog.findById(id) },
            selectedLocale,
        )
        val likelyGroup = groups.firstOrNull { group -> group.stages.any { it.id == likelyId } }
        if (likelyGroup != null) {
            // The game prints the centred card's energy, so a disagreement on cost rules a record out
            // before the player is asked anything. `South Africa` in the Aku Realms collapses from
            // eleven records to the one whose cost is on screen.
            val byEnergy = if (shownEnergy >= 0) {
                likelyGroup.stages.filter { it.energy == shownEnergy }
            } else {
                emptyList()
            }
            val resolved = when {
                likelyGroup.stages.size == 1 -> likelyGroup.stages.single()
                byEnergy.size == 1 -> byEnergy.single()
                else -> null
            }
            if (resolved != null) {
                root.addView(label("Centered card suggestion · please verify", 13f, ACCENT_DEEP))
                root.addView(actionButton("Likely selected: ${candidateName(resolved, catalog)}") {
                    showStageDetails(resolved)
                })
            } else {
                val shown = if (byEnergy.isNotEmpty()) byEnergy else likelyGroup.stages
                // Several chapters share this name (Aku Realms reuses the Empire of Cats labels), so
                // the card is known but the record is not. Offering one of them as "likely selected"
                // is how a scan of Aku Realms South Africa opened the Empire of Cats stage.
                root.addView(
                    label(
                        "Centered card · ${shown.size} stages are called \"${likelyGroup.name}\"",
                        13f,
                        ACCENT_DEEP,
                    ),
                )
                root.addView(
                    actionButton("Choose which \"${likelyGroup.name}\"") {
                        showDuplicates(StageNameGroup(likelyGroup.name, shown), catalog)
                    },
                )
            }
        }
        groups.filterNot { it === likelyGroup }.take(MAX_CANDIDATE_GROUPS).forEach { group ->
            if (group.stages.size == 1) {
                val stage = group.stages.single()
                root.addView(actionButton(candidateName(stage, catalog)) { showStageDetails(stage) })
            } else {
                root.addView(actionButton("${group.name} · ${group.stages.size} stages") { showDuplicates(group, catalog) })
            }
        }
        showResultRoot(root)
    }

    /**
     * Lists every record that shares a name, labelled with the stats the game screen also shows.
     * The selected card displays its Energy, and the local catalog gives each record a distinct
     * (Energy, Base HP) pair, so the player can tell the chapters apart without the app guessing.
     */
    private fun showDuplicates(group: StageNameGroup, catalog: StageCatalog) {
        val root = createCard()
        root.addView(headerRow(group.name, showBack = true) { showCandidates(lastCandidateIntent) })
        root.addView(
            label(
                "${group.stages.size} stages share this name. Pick the chapter your game is showing.",
                13f,
                INK_SOFT,
            ),
        )
        // Chapter order, not match order: an unordered list is what made a tap land on the
        // neighbouring chapter's record and show the wrong enemy lineup.
        group.stages.sortedBy { it.id }.forEach { stage ->
            root.addView(actionButton(recordLabel(stage, catalog)) { showStageDetails(stage) })
        }
        showResultRoot(root)
    }

    /**
     * A duplicate row is labelled with the chapter it belongs to first. Stats alone were useless to
     * a player who has never run the stage: nobody can tell eleven `South Africa` records apart by
     * energy, but `Aku Realms` versus `Empire of Cats Ch.1` is the thing they can actually see.
     */
    private fun recordLabel(stage: StageRecord, catalog: StageCatalog): String {
        val chapter = catalog.chapterNameFor(stage) ?: catalog.seriesNameFor(stage)
        val stats = stage.energy?.let { "Energy $it" }.orEmpty()
        return when {
            chapter != null && stats.isNotEmpty() -> "$chapter · $stats"
            chapter != null -> chapter
            stats.isNotEmpty() -> stats
            else -> "No stats recorded"
        }
    }

    /**
     * Localized stage name plus the tower it belongs to. The local stage file only stores the entry
     * label, so without the tower every "Floor 42" candidate looks identical.
     */
    private fun candidateName(stage: StageRecord, catalog: StageCatalog): String {
        val name = StageCandidateGroups.displayName(stage, selectedLocale)
        val group = catalog.seriesNameFor(stage) ?: catalog.chapterNameFor(stage) ?: return name
        return "$name · $group"
    }

    private fun showStageDetails(stage: StageRecord) {
        val root = createCard()
        val catalog = (application as BattleCatsApplication).stageCatalogRepository.getCatalog()
        root.addView(headerRow(candidateName(stage, catalog), showBack = true) { showCandidates(lastCandidateIntent) })
        val stats = listOfNotNull(
            stage.baseHealth?.let { "Base HP $it" },
            stage.energy?.let { "Energy $it" },
            stage.difficulty.takeIf(String::isNotBlank)?.let { "Difficulty $it" },
        ).joinToString(" · ")
        if (stats.isNotBlank()) root.addView(label(stats, 14f, INK_SOFT))
        val enemyIds = stage.enemySpawns.map { it.enemyId }.distinct()
        if (enemyIds.isNotEmpty()) {
            root.addView(label("Enemies", 16f, ACCENT_DEEP, bold = true))
            val strip = IconFlowLayout(this).apply {
                horizontalGap = dp(6)
                verticalGap = dp(6)
                setPadding(0, dp(4), 0, dp(4))
            }
            enemyIds.take(MAX_ENEMY_ICONS).forEach { enemyId ->
                val icon = loadEnemyIcon(enemyId) ?: return@forEach
                strip.addView(ImageView(this).apply {
                    setImageBitmap(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Enemy $enemyId. Tap for its stats."
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showEnemyDetails(stage, enemyId) }
                }, android.view.ViewGroup.LayoutParams(dp(44), dp(44)))
            }
            if (strip.isNotEmpty()) {
                root.addView(strip)
                root.addView(label("Tap an enemy for its stats", 11f, INK_SOFT))
            }
        }
        showResultRoot(root)
    }

    /**
     * Intrinsic stats for one enemy: the numbers that do not change with a stage's magnification.
     * Reachable from the icon strip, with a back arrow that returns to the stage card.
     */
    private fun showEnemyDetails(stage: StageRecord, enemyId: Int) {
        val stats = (application as BattleCatsApplication).enemyStatsRepository.find(enemyId)
        val root = createCard()
        root.addView(headerRow(stats?.name ?: "Enemy $enemyId", showBack = true) { showStageDetails(stage) })

        if (stats == null) {
            loadEnemyIcon(enemyId)?.let { sprite ->
                root.addView(
                    ImageView(this).apply {
                        setImageBitmap(sprite)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = null
                    },
                    LinearLayout.LayoutParams(dp(84), dp(84)),
                )
            }
            root.addView(label("No intrinsic stats for this enemy in the local data.", 14f, INK_SOFT))
        } else {
            // Sprite on the left, a two-column table on the right. Stacking every stat vertically
            // left the right half of a landscape card empty.
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(6))
            }
            loadEnemyIcon(enemyId)?.let { sprite ->
                body.addView(
                    ImageView(this).apply {
                        setImageBitmap(sprite)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = null
                    },
                    LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(14) },
                )
            }
            val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            table.addView(statRow("Traits", stats.traitLabel, ACCENT_DEEP))
            table.addView(statRow("Attack", "${stats.attackLabel} · Range ${stats.range}"))
            table.addView(statRow("Speed", "${stats.speed} · Knockbacks ${stats.knockbacks}"))
            table.addView(
                statRow(
                    "Abilities",
                    if (stats.abilities.isEmpty()) "None" else stats.abilities.joinToString(" · "),
                ),
            )
            body.addView(table, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            root.addView(body)
        }
        root.addView(label("Enemy #$enemyId", 11f, INK_SOFT))
        showResultRoot(root)
    }

    /** One `label | value` line of the enemy stat table. */
    private fun statRow(name: String, value: String, valueColor: Int = INK): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(
            TextView(this).apply {
                text = name
                textSize = 12f
                setTextColor(INK_SOFT)
            },
            LinearLayout.LayoutParams(dp(72), WRAP_CONTENT),
        )
        row.addView(
            TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(valueColor)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        return row
    }

    private var lastCandidateIntent: Intent = Intent()

    private fun showMessageCard(message: String) {
        val root = createCard()
        root.addView(headerRow("Stage helper", showBack = false) {})
        root.addView(label(message, 15f, INK_SOFT))
        showResultRoot(root)
    }

    /**
     * Shows the cat that the scan recognised, at the level printed on the player's screen.
     *
     * The bundled table is a single reference level (30), so HP and attack are re-projected with
     * [CatLevelCurve] and the card says so — a scaled number presented as if it were read straight
     * from the game would be a lie.
     */
    private fun showCat(intent: Intent) {
        val unitId = intent.getIntExtra(EXTRA_CAT_UNIT_ID, -1)
        if (unitId <= 0) return
        val formIndex = intent.getIntExtra(EXTRA_CAT_FORM_INDEX, 0)
        val readLevel = intent.getIntExtra(EXTRA_CAT_LEVEL, -1)
        val readPlus = intent.getIntExtra(EXTRA_CAT_PLUS, -1)
        val scannedName = intent.getStringExtra(EXTRA_CAT_NAME).orEmpty()
        val confidence = intent.getFloatExtra(EXTRA_CAT_CONFIDENCE, 0f)

        val app = application as BattleCatsApplication
        val region = if (selectedLocale == StageLocale.JP) GameRegion.JP else GameRegion.EN
        val stats = app.unitStatsRepository.stats(region, unitId)

        val root = createCard()
        root.addView(headerRow("Cat scan", showBack = false) {})
        if (stats == null || formIndex !in stats.forms.indices) {
            root.addView(
                label(
                    "$scannedName was read, but this build has no stat row for it. The bundled catalogue is a snapshot.",
                    15f,
                    ACCENT_DEEP,
                ),
            )
            showResultRoot(root)
            return
        }

        val form = stats.forms[formIndex]
        val reference = stats.level
        // Plus levels grow stats like normal levels, so 20 +90 projects as level 110.
        val shownLevel = if (readLevel > 0) readLevel + (if (readPlus > 0) readPlus else 0) else reference
        val hp = CatLevelCurve.project(form.health, reference, shownLevel)
        val attack = CatLevelCurve.project(form.attack, reference, shownLevel)
        val dps = if (form.attackCycleSeconds > 0.0) {
            (attack / form.attackCycleSeconds).toInt()
        } else {
            form.dps
        }

        root.addView(label(form.name, 20f, INK, bold = true))
        val subtitle = buildString {
            append(formLabel(formIndex))
            append("  ·  Lv ")
            if (readLevel > 0) {
                append(readLevel)
                if (readPlus > 0) append(" +").append(readPlus)
            } else {
                append(shownLevel).append(" (level not read)")
            }
        }
        root.addView(label(subtitle, 13f, INK_SOFT))
        root.addView(statRow("Health", formatStat(hp)))
        root.addView(statRow("Attack", formatStat(attack)))
        root.addView(statRow("DPS", formatStat(dps)))
        root.addView(statRow("Range", form.range.toString()))
        root.addView(statRow("Speed", "${form.speedPerSecond}/s"))

        val provenance = if (readLevel > 0 && shownLevel != reference) {
            "HP/ATK scaled from the Lv $reference table. Range, cost and speed do not change with level."
        } else {
            "Straight from the Lv $reference table."
        }
        root.addView(label(provenance, 11f, INK_SOFT))
        if (scannedName.isNotBlank() && !scannedName.equals(form.name, ignoreCase = true)) {
            root.addView(label("Read as: \"$scannedName\"", 11f, INK_SOFT))
        }
        Log.i(TAG, "cat scan: $unitId form $formIndex level $shownLevel conf $confidence")
        showResultRoot(root)
    }

    private fun statRow(name: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(label(name, 14f, INK_SOFT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(value, 15f, INK, bold = true))
        return row
    }

    private fun formatStat(value: Int): String = String.format(Locale.ROOT, "%,d", value)

    private fun showResultRoot(root: View) {
        removeResult()
        val scrollableRoot = ScrollView(this).apply {
            addView(root)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            width = (resources.displayMetrics.widthPixels * CARD_WIDTH_FRACTION).toInt()
                .coerceAtLeast(dp(300))
            height = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        }
        runCatching { windowManager.addView(scrollableRoot, params) }.onSuccess {
            resultView = scrollableRoot
            resultParams = params
        }
    }

    /**
     * Card header: the title, plus small round icon buttons on the right.
     *
     * Full-width "Back" / "Close" rows cost two 54 dp bands on every card. A browser-style back arrow
     * and an X in the corner do the same job and let the card itself stay short.
     */
    private fun headerRow(title: String, showBack: Boolean, onBack: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val heading = TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(heading, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (showBack) row.addView(circleButton(R.drawable.ic_back, "Back to candidates", onBack))
        row.addView(circleButton(R.drawable.ic_close, "Close", ::closeResult))
        return row
    }

    private fun circleButton(iconRes: Int, description: String, action: () -> Unit): View {
        val size = dp(38)
        val params = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(8) }
        return ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = circleBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = params
        }
    }

    private fun circleBackground() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(PAPER_LIGHT)
        setStroke(dp(3), INK)
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(10))
        background = cardBackground()
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }

    /**
     * The card's rows are hand-drawn pills: yellow for the action the player is most likely to want,
     * paper for the rest. A wrapper supplies the gap between rows, because the card is a plain
     * LinearLayout and `addView` is called with default layout params at every call site.
     */
    private fun actionButton(title: String, primary: Boolean = true, action: () -> Unit): View {
        val pill = TextView(this).apply {
            text = title
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = pillBackground(primary)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        return FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(8))
            addView(pill, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun loadEnemyIcon(enemyId: Int) = runCatching {
        val filename = "enemies/enemy_${String.format(Locale.ROOT, "%03d", enemyId)}.png"
        assets.open(filename).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun closeResult() {
        removeResult()
        bubble?.visibility = View.VISIBLE
    }

    private fun showStopTarget() {
        if (stopTarget != null) return
        val view = TextView(this).apply {
            text = getString(com.projectnyanko.companion.R.string.stage_stop_drop)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(INK)
            background = roundedBackground(0xF2C0453A.toInt(), 32)
        }
        val params = WindowManager.LayoutParams(
            dp(220),
            dp(58),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(38)
        }
        runCatching { windowManager.addView(view, params) }.onSuccess {
            stopTarget = view
            stopTargetParams = params
        }
    }

    private fun isDroppedOnStopTarget(rawX: Float, rawY: Float): Boolean {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return rawY >= metrics.heightPixels - dp(120) && abs(rawX - metrics.widthPixels / 2f) <= dp(130)
    }

    private fun snapBubbleToNearestEdge(rawX: Float, params: WindowManager.LayoutParams) {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        params.gravity = Gravity.TOP or (if (rawX < metrics.widthPixels / 2f) Gravity.START else Gravity.END)
        params.x = dp(12)
        params.y = params.y.coerceIn(dp(40), (metrics.heightPixels - dp(120)).coerceAtLeast(dp(40)))
        bubble?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
    }

    private fun removeStopTarget() {
        stopTarget?.let { runCatching { windowManager.removeView(it) } }
        stopTarget = null
        stopTargetParams = null
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    private fun removeResult() {
        // Only detach the window. Bitmaps are left to the garbage collector: this device draws
        // overlay windows in software, so a draw pass scheduled before the removal can still run
        // after this call and would crash on a recycled bitmap.
        resultView?.let { runCatching { windowManager.removeView(it) } }
        resultView = null
        resultParams = null
    }

    private fun cardBackground() = GradientDrawable().apply {
        setColor(PAPER_DEEP)
        cornerRadius = dp(22).toFloat()
        setStroke(dp(3), INK)
    }

    private fun pillBackground(primary: Boolean) = GradientDrawable().apply {
        setColor(if (primary) ACCENT else PAPER_LIGHT)
        cornerRadius = dp(24).toFloat()
        setStroke(dp(3), INK)
    }

    private fun bubbleBackground() = bubbleBackground(ACCENT)

    private fun bubbleBackground(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(3), INK)
    }

    // ------------------------------------------------------------------ scan mode

    private fun currentScanMode(): ScanMode =
        ScanMode.fromName(
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(PREFERENCE_SCAN_MODE, null),
        )

    private fun toggleScanMode() {
        val next = currentScanMode().toggled()
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(PREFERENCE_SCAN_MODE, next.name)
            .apply()
        applyScanModeToBubble()
        val message = when (next) {
            ScanMode.STAGE -> "Stage mode: the paw reads stage names"
            ScanMode.CAT -> "Cat mode: the paw reads cat name + level"
        }
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        Log.i(TAG, "scan mode -> ${next.name}")
    }

    /** The paw is amber for stages and teal for cats, so the mode is visible without a label. */
    private fun applyScanModeToBubble() {
        val view = bubble ?: return
        val mode = currentScanMode()
        view.background = bubbleBackground(
            when (mode) {
                ScanMode.STAGE -> ACCENT
                ScanMode.CAT -> CAT_MODE_FILL
            },
        )
        view.contentDescription = when (mode) {
            ScanMode.STAGE -> "Battle Cats stage helper. Tap to scan a stage, hold to switch to cat stats."
            ScanMode.CAT -> "Battle Cats cat helper. Tap to scan a cat, hold to switch to stage info."
        }
    }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0x5585DED4)
    }

    private fun overlayWindowType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun String.toLocale() = if (equals("JP", ignoreCase = true)) StageLocale.JP else StageLocale.EN

    private fun startForegroundSafely() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Stage helper", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Battle Cats stage helper is ready")
            .setContentText("Tap the floating paw to scan one screen.")
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop helper",
                    android.app.PendingIntent.getService(
                        this,
                        NOTIFICATION_ID,
                        Intent(this, StageOverlayService::class.java).setAction(ACTION_STOP),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "StageOverlay"
        private const val INK = 0xFF2B2118.toInt()
        private const val INK_SOFT = 0xFF6E5C46.toInt()
        private const val ACCENT = 0xFFF5C55A.toInt()
        private const val ACCENT_DEEP = 0xFFD9992B.toInt()
        private const val PAPER_DEEP = 0xFFF2E0C4.toInt()
        private const val PAPER_LIGHT = 0xFFFFFBF1.toInt()

        private const val CHANNEL_ID = "stage_helper"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_ENEMY_ICONS = 30
        private const val MAX_CANDIDATE_GROUPS = 5

        /**
         * The card used to span the whole screen, which put every enemy sprite on one row and made
         * the box as wide as the display. A narrower card wraps the sprite strip instead, so a long
         * lineup grows the box downwards rather than sideways.
         */
        private const val CARD_WIDTH_FRACTION = 0.72f
        const val ACTION_SHOW_BUBBLE = "com.projectnyanko.companion.stage.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.projectnyanko.companion.stage.HIDE_BUBBLE"
        const val PREFERENCES = "stage_helper"
        const val PREFERENCE_REGION = "region"
        const val PREFERENCE_SCAN_MODE = "scan_mode"
        private const val LONG_PRESS_MS = 500L
        private const val CAT_MODE_FILL = 0xFF7FD8CE.toInt()
        const val ACTION_SHOW_RESULT = "com.projectnyanko.companion.stage.SHOW_RESULT"
        const val ACTION_SHOW_CAT = "com.projectnyanko.companion.stage.SHOW_CAT"
        const val EXTRA_CAT_UNIT_ID = "cat_unit_id"
        const val EXTRA_CAT_FORM_INDEX = "cat_form_index"
        const val EXTRA_CAT_LEVEL = "cat_level"
        const val EXTRA_CAT_PLUS = "cat_plus"
        const val EXTRA_CAT_NAME = "cat_name"
        const val EXTRA_CAT_CONFIDENCE = "cat_confidence"
        const val ACTION_CLOSE_RESULT = "com.projectnyanko.companion.stage.CLOSE_RESULT"
        const val ACTION_STOP = "com.projectnyanko.companion.stage.STOP_HELPER"
        const val ACTION_SHOW_MESSAGE = "com.projectnyanko.companion.stage.SHOW_MESSAGE"
        const val EXTRA_REGION = "stage_region"
        const val EXTRA_SCAN_MODE = "scan_mode"
        const val EXTRA_STAGE_IDS = "stage_ids"
        const val EXTRA_LIKELY_STAGE_ID = "likely_stage_id"
        const val EXTRA_SELECTED_ENERGY = "selected_energy"
        const val EXTRA_OCR_TEXTS = "ocr_texts"
        const val EXTRA_RECOGNIZED_TEXT = "recognized_text"
        const val EXTRA_MESSAGE = "message"

        fun startBubble(context: Context, locale: StageLocale) {
            val intent = Intent(context, StageOverlayService::class.java)
                .setAction(ACTION_SHOW_BUBBLE)
                .putExtra(EXTRA_REGION, locale.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun hideBubble(context: Context, locale: StageLocale) {
            context.startService(
                Intent(context, StageOverlayService::class.java)
                    .setAction(ACTION_HIDE_BUBBLE)
                    .putExtra(EXTRA_REGION, locale.name),
            )
        }

        fun showMessage(context: Context, locale: String, message: String) {
            val intent = Intent(context, StageOverlayService::class.java)
                .setAction(ACTION_SHOW_MESSAGE)
                .putExtra(EXTRA_REGION, locale)
                .putExtra(EXTRA_MESSAGE, message)
            context.startService(intent)
        }
    }
}
