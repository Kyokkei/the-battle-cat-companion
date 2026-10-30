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
import java.nio.charset.StandardCharsets

/**
 * CPU ONNX Runtime adapter for PP-OCR CTC recognizers, following the PaddleOCR input/decode contract.
 *
 * The recognizer is told nothing about screen geometry: it receives one already-trimmed text line and
 * returns its characters. Width follows the crop's own aspect ratio, so a wide line keeps enough time
 * steps to spell every character instead of being squashed into a fixed input width.
 */
internal class PpOcrCtcRecognizer(
    modelFile: File,
    dictionaryFile: File,
    private val label: String,
) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val dictionary: List<String>
    private val session: OrtSession

    init {
        try {
            require(modelFile.isFile && modelFile.length() > 0L) { "$label model is missing." }
            require(dictionaryFile.isFile && dictionaryFile.length() > 0L) { "$label dictionary is missing." }
            dictionary = dictionaryFile.readLines(StandardCharsets.UTF_8).filter(String::isNotEmpty)
            require(dictionary.isNotEmpty()) { "$label dictionary is empty." }
            session = environment.createSession(modelFile.absolutePath, options)
        } catch (error: Throwable) {
            runCatching { options.close() }
            throw error
        }
    }

    fun recognize(image: Bitmap): OcrTextResult {
        require(!image.isRecycled && image.width > 0 && image.height > 0) { "OCR crop is empty." }
        val width = maxOf(8, kotlin.math.ceil(image.width.toDouble() * INPUT_HEIGHT / image.height).toInt())
        require(width <= MAX_INPUT_WIDTH) { "$label crop is too wide." }
        val scaled = image.scale(width, INPUT_HEIGHT)
        return try {
            val tensorData = toBgrNormalized(scaled)
            val buffer = ByteBuffer.allocateDirect(tensorData.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(tensorData); rewind() }
            OnnxTensor.createTensor(
                environment,
                buffer,
                longArrayOf(1, 3, INPUT_HEIGHT.toLong(), width.toLong()),
            ).use { tensor ->
                val inputName = session.inputNames.singleOrNull()
                    ?: throw IOException("$label expected exactly one model input.")
                session.run(mapOf(inputName to tensor)).use { result ->
                    val output = OnnxOutputValidators.batchTimeRows(result.get(0).value, label)
                    if (output.isEmpty() || output[0].isEmpty()) throw IOException("$label output shape mismatch.")
                    PpOcrCtcDecoder.decode(output, dictionary)
                }
            }
        } finally {
            if (scaled !== image) scaled.recycle()
        }
    }

    override fun close() {
        try {
            session.close()
        } finally {
            options.close()
        }
    }

    private fun toBgrNormalized(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val planeSize = width * height
        val values = FloatArray(3 * planeSize)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            values[index] = ((pixel and 0xff) / 255f - 0.5f) / 0.5f
            values[planeSize + index] = (((pixel ushr 8) and 0xff) / 255f - 0.5f) / 0.5f
            values[2 * planeSize + index] = (((pixel ushr 16) and 0xff) / 255f - 0.5f) / 0.5f
        }
        return values
    }

    private companion object {
        const val INPUT_HEIGHT = 48
        const val MAX_INPUT_WIDTH = 4096
    }
}
