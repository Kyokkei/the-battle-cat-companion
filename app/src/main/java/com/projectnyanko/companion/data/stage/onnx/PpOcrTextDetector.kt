package com.projectnyanko.companion.data.stage.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * PP-OCRv5 DB text detector.
 *
 * The app used to cut the carousel out of the frame with fixed screen fractions, which only held for
 * the 2400x1080 capture they were measured on and clipped the highlighted card, because the selected
 * card is drawn larger and lower than its neighbours. Detection removes that assumption entirely:
 * the frame is scaled to a fixed long side, every text box on screen is found, and the boxes are
 * reported in frame pixels. Nothing here depends on the screen resolution or aspect ratio.
 */
internal class PpOcrTextDetector(modelFile: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val session: OrtSession

    init {
        try {
            require(modelFile.isFile && modelFile.length() > 0L) { "Text detector model is missing." }
            session = environment.createSession(modelFile.absolutePath, options)
        } catch (error: Throwable) {
            runCatching { options.close() }
            throw error
        }
    }

    /** Text boxes in [frame] pixel coordinates, unsorted and unmerged. */
    fun detect(frame: Bitmap): List<TextBox> {
        require(!frame.isRecycled && frame.width > 0 && frame.height > 0) { "Captured screen is empty." }
        val (inputWidth, inputHeight) = detectorInputSize(frame.width, frame.height)
        val scaled = frame.scale(inputWidth, inputHeight)
        return try {
            val data = toImageNetNormalized(scaled)
            val buffer = ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(data); rewind() }
            OnnxTensor.createTensor(
                environment,
                buffer,
                longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()),
            ).use { tensor ->
                val inputName = session.inputNames.singleOrNull()
                    ?: throw IOException("PP-OCR detector expected exactly one model input.")
                session.run(mapOf(inputName to tensor)).use { result ->
                    val probability = OnnxOutputValidators.flattenCache(
                        result.get(0).value,
                        inputWidth * inputHeight,
                        "PP-OCR detector",
                    )
                    extractBoxes(probability, inputWidth, inputHeight, frame.width, frame.height)
                }
            }
        } finally {
            if (scaled !== frame) scaled.recycle()
        }
    }

    override fun close() {
        try {
            session.close()
        } finally {
            options.close()
        }
    }

    /**
     * Scales the frame so its long side is [LONG_SIDE] and both sides are multiples of 32, which the
     * detector's encoder needs. Detection is run on the whole frame rather than a cropped strip so a
     * phone with an unusual aspect ratio cannot push the stage cards out of the processed area.
     */
    private fun detectorInputSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = LONG_SIDE.toFloat() / maxOf(width, height)
        val scaledWidth = (width * scale).toInt().coerceAtLeast(STRIDE)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(STRIDE)
        val roundedWidth = ((scaledWidth + STRIDE / 2) / STRIDE) * STRIDE
        val roundedHeight = ((scaledHeight + STRIDE / 2) / STRIDE) * STRIDE
        return roundedWidth.coerceAtLeast(STRIDE) to roundedHeight.coerceAtLeast(STRIDE)
    }

    /** Detector preprocessing is ImageNet mean/std in RGB order, not the recognizer's [-1, 1]. */
    private fun toImageNetNormalized(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val planeSize = width * height
        val values = FloatArray(3 * planeSize)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            values[index] = ((pixel ushr 16 and 0xff) / 255f - DET_MEAN_RED) / DET_STD_RED
            values[planeSize + index] = ((pixel ushr 8 and 0xff) / 255f - DET_MEAN_GREEN) / DET_STD_GREEN
            values[2 * planeSize + index] = ((pixel and 0xff) / 255f - DET_MEAN_BLUE) / DET_STD_BLUE
        }
        return values
    }

    /**
     * DB post-processing: threshold the probability map, group touching pixels, and grow each group
     * by the unclip distance so the box covers the whole glyph run instead of its core strokes.
     * Boxes stay axis-aligned; stage names are always drawn horizontally, so the rotated-polygon
     * handling a general OCR stack needs would only add cost.
     */
    private fun extractBoxes(
        probability: FloatArray,
        inputWidth: Int,
        inputHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): List<TextBox> {
        val visited = BooleanArray(probability.size)
        val stack = IntArray(probability.size)
        val scaleX = frameWidth.toFloat() / inputWidth
        val scaleY = frameHeight.toFloat() / inputHeight
        val boxes = ArrayList<TextBox>()
        for (start in probability.indices) {
            if (visited[start] || probability[start] <= DETECTION_THRESHOLD) continue
            var stackSize = 0
            stack[stackSize++] = start
            visited[start] = true
            var minX = inputWidth
            var maxX = -1
            var minY = inputHeight
            var maxY = -1
            var area = 0
            var scoreTotal = 0f
            while (stackSize > 0) {
                val index = stack[--stackSize]
                val y = index / inputWidth
                val x = index - y * inputWidth
                area++
                scoreTotal += probability[index]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (neighbourY in maxOf(0, y - 1)..minOf(inputHeight - 1, y + 1)) {
                    val rowOffset = neighbourY * inputWidth
                    for (neighbourX in maxOf(0, x - 1)..minOf(inputWidth - 1, x + 1)) {
                        val neighbour = rowOffset + neighbourX
                        if (!visited[neighbour] && probability[neighbour] > DETECTION_THRESHOLD) {
                            visited[neighbour] = true
                            stack[stackSize++] = neighbour
                        }
                    }
                }
            }
            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            if (boxWidth < MIN_BOX_SIDE || boxHeight < MIN_BOX_SIDE) continue
            val score = scoreTotal / area
            if (score < BOX_SCORE_THRESHOLD) continue
            if (area.toFloat() / (boxWidth * boxHeight) < BOX_FILL_THRESHOLD) continue
            val unclip = area * UNCLIP_RATIO / (2 * (boxWidth + boxHeight)).toFloat()
            boxes += TextBox(
                left = ((minX - unclip) * scaleX).toInt().coerceAtLeast(0),
                top = ((minY - unclip) * scaleY).toInt().coerceAtLeast(0),
                right = ((maxX + 1 + unclip) * scaleX).toInt().coerceAtMost(frameWidth),
                bottom = ((maxY + 1 + unclip) * scaleY).toInt().coerceAtMost(frameHeight),
                score = score,
            )
        }
        return boxes
    }

    private companion object {
        const val LONG_SIDE = 960
        const val STRIDE = 32
        const val DETECTION_THRESHOLD = 0.30f
        const val BOX_SCORE_THRESHOLD = 0.55f
        const val BOX_FILL_THRESHOLD = 0.55f
        const val UNCLIP_RATIO = 1.6f
        const val MIN_BOX_SIDE = 2

        const val DET_MEAN_RED = 0.485f
        const val DET_MEAN_GREEN = 0.456f
        const val DET_MEAN_BLUE = 0.406f
        const val DET_STD_RED = 0.229f
        const val DET_STD_GREEN = 0.224f
        const val DET_STD_BLUE = 0.225f
    }
}
