package com.projectnyanko.companion.ui.stage

import android.graphics.Bitmap
import android.util.Log
import com.projectnyanko.companion.data.stage.StageOcrModelStore
import com.projectnyanko.companion.data.stage.onnx.MangaOcrRecognizer
import com.projectnyanko.companion.data.stage.onnx.PpOcrCtcRecognizer
import com.projectnyanko.companion.data.stage.onnx.PpOcrTextDetector
import com.projectnyanko.companion.data.stage.onnx.TextBox
import com.projectnyanko.companion.data.stage.onnx.TextLineBuilder
import com.projectnyanko.companion.domain.stage.OcrStageLine
import com.projectnyanko.companion.domain.stage.StageLocale

/**
 * ONNX Runtime OCR for the stage-name cards on the Battle Cats stage-select screen.
 *
 * The frame is not cut with hard-coded screen fractions any more. A DB text detector finds every
 * text box on screen, adjacent boxes on one line are merged back into one name, and each line is
 * recognized. That keeps the result independent of screen resolution and aspect ratio, and it reads
 * the highlighted card, which is drawn larger and lower than its neighbours and was being clipped by
 * the previous fixed band.
 */
class StageOcrEngine(private val modelStore: StageOcrModelStore) : AutoCloseable {
    private var detector: PpOcrTextDetector? = null
    private var englishRecognizer: PpOcrCtcRecognizer? = null
    private var japaneseRecognizer: MangaOcrRecognizer? = null
    private var activeLocale: StageLocale? = null
    private var closed = false

    @Synchronized
    fun recognize(screen: Bitmap, locale: StageLocale): List<OcrStageLine> {
        check(!closed) { "OCR engine is closed." }
        require(!screen.isRecycled && screen.width > 0 && screen.height > 0) { "Captured screen is empty." }
        switchLocale(locale)
        activeLocale = locale
        val detected = TextLineBuilder.merge(detector().detect(screen))
        val kept = detected.filter { box -> isCarouselLine(box, screen.width, screen.height) }
        Log.i(TAG, "detector found ${detected.size} lines, ${kept.size} kept as labels")
        val lines = if (locale == StageLocale.JP) centeredFirst(kept, screen.width) else kept
        return recognizeBoxes(screen, locale, lines)
    }

    /**
     * Every text line on screen, without the stage-carousel filter.
     *
     * The cat scanner needs the name bar and the `Level` label, which sit outside the band the stage
     * reader keeps, so it asks for the unfiltered set and does its own selection.
     */
    @Synchronized
    fun recognizeAllLines(screen: Bitmap, locale: StageLocale): List<OcrStageLine> {
        check(!closed) { "OCR engine is closed." }
        require(!screen.isRecycled && screen.width > 0 && screen.height > 0) { "Captured screen is empty." }
        switchLocale(locale)
        activeLocale = locale
        val detected = TextLineBuilder.merge(detector().detect(screen))
        Log.i(TAG, "cat scan: detector found ${detected.size} lines")
        val ordered = if (locale == StageLocale.JP) centeredFirst(detected, screen.width) else detected
        return recognizeBoxes(screen, locale, ordered)
    }

    private fun switchLocale(locale: StageLocale) {
        if (activeLocale == null || activeLocale == locale) return
        if (locale == StageLocale.JP) {
            englishRecognizer?.close()
            englishRecognizer = null
        } else {
            japaneseRecognizer?.close()
            japaneseRecognizer = null
        }
    }

    private fun recognizeBoxes(
        screen: Bitmap,
        locale: StageLocale,
        boxes: List<TextBox>,
    ): List<OcrStageLine> =
        boxes.mapNotNull { box ->
            val crop = Bitmap.createBitmap(screen, box.left, box.top, box.width, box.height)
            try {
                val result = if (locale == StageLocale.JP) japaneseRecognizer().recognize(crop)
                else englishRecognizer().recognize(crop)
                if (result.text.isBlank()) return@mapNotNull null
                OcrStageLine(
                    text = result.text,
                    confidence = result.confidence.coerceIn(0f, 1f),
                    top = box.top.toFloat() / screen.height,
                    left = box.left.toFloat() / screen.width,
                    bottom = box.bottom.toFloat() / screen.height,
                    right = box.right.toFloat() / screen.width,
                )
            } finally {
                crop.recycle()
            }
        }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        activeLocale = null
        try {
            englishRecognizer?.close()
        } finally {
            englishRecognizer = null
            try {
                japaneseRecognizer?.close()
            } finally {
                japaneseRecognizer = null
                detector?.close()
                detector = null
            }
        }
    }

    private fun detector(): PpOcrTextDetector = detector ?: run {
        val models = modelStore.ensureModels(StageLocale.EN)
        PpOcrTextDetector(models.getValue(DETECTOR_MODEL)).also { detector = it }
    }

    private fun englishRecognizer(): PpOcrCtcRecognizer = englishRecognizer ?: run {
        val models = modelStore.ensureModels(StageLocale.EN)
        PpOcrCtcRecognizer(
            modelFile = models.getValue(EN_MODEL),
            dictionaryFile = models.getValue(EN_DICTIONARY),
            label = "English PP-OCRv6",
        ).also { englishRecognizer = it }
    }

    private fun japaneseRecognizer(): MangaOcrRecognizer = japaneseRecognizer ?: run {
        val encoder = modelStore.ensureModels(StageLocale.JP).getValue(JA_ENCODER)
        MangaOcrRecognizer(encoder.parentFile ?: error("Japanese OCR model directory is invalid.")).also {
            japaneseRecognizer = it
        }
    }

    /**
     * The carousel sits in the upper part of the screen, above the map. Keeping only lines whose
     * height is a plausible card label — a share of the frame height, never a pixel count — also
     * drops the `Energy`/`Cleared` captions, the bottom button bar, and texture noise the detector
     * may pick up. Nothing here is tied to one device's resolution.
     */
    private fun isCarouselLine(box: TextBox, width: Int, height: Int): Boolean {
        val heightRatio = box.height.toFloat() / height
        if (heightRatio < MIN_LABEL_HEIGHT_RATIO || heightRatio > MAX_LABEL_HEIGHT_RATIO) return false
        if (box.width.toFloat() / width > MAX_LABEL_WIDTH_RATIO) return false
        if (box.top.toFloat() / height > MAX_LABEL_TOP_RATIO) return false
        return box.width >= box.height
    }

    /**
     * Manga OCR is a full encoder/decoder pass per line, so the Japanese path only recognizes the
     * lines closest to the horizontal centre: the selected card is always the centred one, and this
     * bounds the work instead of running the decoder over every label on screen.
     */
    private fun centeredFirst(boxes: List<TextBox>, frameWidth: Int): List<TextBox> {
        return boxes.sortedBy { kotlin.math.abs(it.centerX / frameWidth - 0.5f) }.take(MAX_JAPANESE_LINES)
    }

    private companion object {
        const val TAG = "StageOcr"

        const val DETECTOR_MODEL = "shared/ppocrv5_det.onnx"
        const val EN_MODEL = "en/PP-OCRv6_small_rec.onnx"
        const val EN_DICTIONARY = "en/ppocrv6_dict.txt"
        const val JA_ENCODER = "ja/encoder.onnx"

        const val MIN_LABEL_HEIGHT_RATIO = 0.015f
        const val MAX_LABEL_HEIGHT_RATIO = 0.20f
        const val MAX_LABEL_WIDTH_RATIO = 0.45f

        /**
         * A chapter's final stage is the only card on its map, and it is drawn lower and larger than
         * a carousel row. Capping at 0.45 dropped it before recognition, which is why `Moon` could
         * not be read on any of the main chapters while the always-carousel Stories of Legend maps
         * were fine.
         */
        const val MAX_LABEL_TOP_RATIO = 0.62f
        const val MAX_JAPANESE_LINES = 3
    }
}
