package com.projectnyanko.companion.data.stage.onnx

import android.graphics.Bitmap
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.charset.StandardCharsets

/** CPU ONNX Runtime adapter using Fukidashi's mobile Manga OCR encoder/decoder cache contract. */
internal class MangaOcrRecognizer(modelDirectory: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val floatBuffers = HashMap<String, FloatBuffer>()
    private val longBuffers = HashMap<String, LongBuffer>()
    private val vocabulary: List<String>
    private val encoder: OrtSession
    private val decoderInit: OrtSession
    private val decoderStep: OrtSession

    init {
        var loadedEncoder: OrtSession? = null
        var loadedInit: OrtSession? = null
        var loadedStep: OrtSession? = null
        try {
            val encoderFile = File(modelDirectory, "encoder.onnx")
            val initFile = File(modelDirectory, "decoder_init.onnx")
            val stepFile = File(modelDirectory, "decoder_step.onnx")
            val vocabularyFile = File(modelDirectory, "vocab.txt")
            require(listOf(encoderFile, initFile, stepFile, vocabularyFile).all { it.isFile && it.length() > 0L }) {
                "Japanese Manga OCR model files are incomplete."
            }
            vocabulary = vocabularyFile.readLines(StandardCharsets.UTF_8)
            require(vocabulary.size == VOCAB_SIZE) { "Japanese Manga OCR vocabulary size mismatch." }
            loadedEncoder = environment.createSession(encoderFile.absolutePath, options)
            loadedInit = environment.createSession(initFile.absolutePath, options)
            loadedStep = environment.createSession(stepFile.absolutePath, options)
        } catch (error: Throwable) {
            runCatching { loadedEncoder?.close() }
            runCatching { loadedInit?.close() }
            runCatching { loadedStep?.close() }
            runCatching { options.close() }
            throw error
        }
        encoder = loadedEncoder ?: error("Manga OCR encoder session was not created.")
        decoderInit = loadedInit ?: error("Manga OCR init decoder session was not created.")
        decoderStep = loadedStep ?: error("Manga OCR step decoder session was not created.")
    }

    @Synchronized
    fun recognize(image: Bitmap): OcrTextResult {
        require(!image.isRecycled && image.width > 0 && image.height > 0) { "Japanese OCR crop is empty." }
        val encoded = encode(letterbox(image))
        var decoder = initializeDecoder(encoded)
        var nextToken = argmax(decoder.logits[0])
        val outputTokens = ArrayList<Int>()
        outputTokens += START_TOKEN
        var confidenceTotal = 0f
        var tokenCount = 0
        if (nextToken != END_TOKEN) {
            outputTokens += nextToken
            confidenceTotal += softmaxConfidence(decoder.logits[0], nextToken)
            tokenCount++
        }

        var cacheLength = 1
        while (nextToken != END_TOKEN && outputTokens.size < MAX_TOKENS && cacheLength < CACHE_LENGTH) {
            decoder = runDecoderStep(encoded, nextToken, cacheLength, decoder.selfKey, decoder.selfValue, decoder.crossKey, decoder.crossValue)
            copyCacheSlice(decoder.selfKey, decoder.selfKeySlice, cacheLength)
            copyCacheSlice(decoder.selfValue, decoder.selfValueSlice, cacheLength)
            nextToken = argmax(decoder.logits[0])
            if (nextToken == END_TOKEN) break
            confidenceTotal += softmaxConfidence(decoder.logits[0], nextToken)
            tokenCount++
            outputTokens += nextToken
            cacheLength++
        }

        val text = buildString {
            outputTokens.forEach { token ->
                if (token >= FIRST_TEXT_TOKEN && token < vocabulary.size) append(vocabulary[token])
            }
        }.replace(WHITESPACE_REGEX, "")
        return OcrTextResult(text, if (tokenCount == 0) 0f else confidenceTotal / tokenCount)
    }

    private fun encode(values: FloatArray): FloatArray =
        floatTensor("encoder_image", values, longArrayOf(1, CHANNELS.toLong(), IMAGE_SIDE.toLong(), IMAGE_SIDE.toLong())).use { input ->
            encoder.run(mapOf(ENCODER_INPUT to input)).use { result ->
                val rows = OnnxOutputValidators.batchTimeRows(result.get(0).value, "Manga OCR encoder")
                if (rows.size != ENCODER_SEQUENCE || rows.any { it.size != HIDDEN_WIDTH }) {
                    throw IOException("Manga OCR encoder output shape mismatch.")
                }
                FloatArray(ENCODER_SEQUENCE * HIDDEN_WIDTH).also { flat ->
                    var index = 0
                    rows.forEach { row -> row.forEach { flat[index++] = it } }
                }
            }
        }

    private fun initializeDecoder(encoded: FloatArray): DecoderState =
        floatTensor("decoder_hidden", encoded, longArrayOf(1, ENCODER_SEQUENCE.toLong(), HIDDEN_WIDTH.toLong())).use { hidden ->
            longTensor("input_ids", longArrayOf(START_TOKEN.toLong()), longArrayOf(1, 1)).use { ids ->
                decoderInit.run(mapOf(ENCODER_STATES to hidden, INPUT_IDS to ids)).use { result ->
                    val logits = OnnxOutputValidators.batchLogits(result.get(0).value, "Manga OCR init")
                    validateLogits(logits)
                    val selfKeySlice = OnnxOutputValidators.flattenCache(result.get(1).value, CACHE_SLICE_ELEMENTS, "init self-key")
                    val selfValueSlice = OnnxOutputValidators.flattenCache(result.get(2).value, CACHE_SLICE_ELEMENTS, "init self-value")
                    val crossKey = OnnxOutputValidators.flattenCache(result.get(3).value, CROSS_CACHE_ELEMENTS, "cross-key")
                    val crossValue = OnnxOutputValidators.flattenCache(result.get(4).value, CROSS_CACHE_ELEMENTS, "cross-value")
                    val selfKey = FloatArray(SELF_CACHE_ELEMENTS)
                    val selfValue = FloatArray(SELF_CACHE_ELEMENTS)
                    copyCacheSlice(selfKey, selfKeySlice, 0)
                    copyCacheSlice(selfValue, selfValueSlice, 0)
                    DecoderState(logits, selfKey, selfValue, selfKeySlice, selfValueSlice, crossKey, crossValue)
                }
            }
        }

    private fun runDecoderStep(
        encoded: FloatArray,
        token: Int,
        cacheLength: Int,
        selfKey: FloatArray,
        selfValue: FloatArray,
        crossKey: FloatArray,
        crossValue: FloatArray,
    ): DecoderState {
        val tensors = ArrayList<OnnxTensor>(7)
        try {
            fun floats(name: String, values: FloatArray, shape: LongArray) = floatTensor(name, values, shape).also(tensors::add)
            fun longs(name: String, values: LongArray, shape: LongArray) = longTensor(name, values, shape).also(tensors::add)
            val inputs = mapOf(
                ENCODER_STATES to floats("decoder_hidden", encoded, longArrayOf(1, ENCODER_SEQUENCE.toLong(), HIDDEN_WIDTH.toLong())),
                INPUT_IDS to longs("input_ids", longArrayOf(token.toLong()), longArrayOf(1, 1)),
                POSITION_IDS to longs("position_ids", longArrayOf(minOf(cacheLength + 1, MAX_POSITION).toLong()), longArrayOf(1, 1)),
                SELF_KEY_CACHE to floats("self_key", selfKey, SELF_CACHE_SHAPE),
                SELF_VALUE_CACHE to floats("self_value", selfValue, SELF_CACHE_SHAPE),
                CROSS_KEY_CACHE to floats("cross_key", crossKey, CROSS_CACHE_SHAPE),
                CROSS_VALUE_CACHE to floats("cross_value", crossValue, CROSS_CACHE_SHAPE),
            )
            return decoderStep.run(inputs).use { result ->
                val logits = OnnxOutputValidators.batchLogits(result.get(0).value, "Manga OCR step")
                validateLogits(logits)
                val selfKeySlice = OnnxOutputValidators.flattenCache(result.get(1).value, CACHE_SLICE_ELEMENTS, "step self-key")
                val selfValueSlice = OnnxOutputValidators.flattenCache(result.get(2).value, CACHE_SLICE_ELEMENTS, "step self-value")
                DecoderState(logits, selfKey, selfValue, selfKeySlice, selfValueSlice, crossKey, crossValue)
            }
        } finally {
            tensors.forEach { runCatching { it.close() } }
        }
    }

    private fun letterbox(source: Bitmap): FloatArray {
        val scale = minOf(IMAGE_SIDE.toFloat() / source.width, IMAGE_SIDE.toFloat() / source.height)
        val width = maxOf(1, minOf(IMAGE_SIDE, (source.width * scale).toInt()))
        val height = maxOf(1, minOf(IMAGE_SIDE, (source.height * scale).toInt()))
        val resized = source.scale(width, height)
        try {
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            val output = FloatArray(CHANNELS * IMAGE_SIDE * IMAGE_SIDE) { 1f }
            val offsetX = (IMAGE_SIDE - width) / 2
            val offsetY = (IMAGE_SIDE - height) / 2
            pixels.forEachIndexed { index, color ->
                val x = index % width
                val y = index / width
                val outputIndex = (y + offsetY) * IMAGE_SIDE + x + offsetX
                output[outputIndex] = ((color ushr 16) and 0xff) / 255f
                output[IMAGE_SIDE * IMAGE_SIDE + outputIndex] = ((color ushr 8) and 0xff) / 255f
                output[2 * IMAGE_SIDE * IMAGE_SIDE + outputIndex] = (color and 0xff) / 255f
            }
            return output
        } finally {
            if (resized !== source) resized.recycle()
        }
    }

    private fun copyCacheSlice(cache: FloatArray, slice: FloatArray, position: Int) {
        if (slice.size != CACHE_SLICE_ELEMENTS || position !in 0 until CACHE_LENGTH) {
            throw IOException("Manga OCR decoder cache slice contract mismatch.")
        }
        for (layer in 0 until LAYERS) for (head in 0 until ATTENTION_HEADS) {
            val source = (layer * ATTENTION_HEADS + head) * HEAD_WIDTH
            val target = ((layer * ATTENTION_HEADS + head) * CACHE_LENGTH + position) * HEAD_WIDTH
            slice.copyInto(cache, target, source, source + HEAD_WIDTH)
        }
    }

    private fun validateLogits(logits: Array<FloatArray>) {
        if (logits.size != 1 || logits[0].size != VOCAB_SIZE) throw IOException("Manga OCR logits shape mismatch.")
        if (logits[0].any { !it.isFinite() }) throw IOException("Manga OCR returned non-finite logits.")
    }

    private fun argmax(values: FloatArray): Int {
        if (values.isEmpty()) throw IOException("Manga OCR returned empty logits.")
        var best = 0
        for (index in 1 until values.size) if (values[index] > values[best]) best = index
        return best
    }

    private fun softmaxConfidence(values: FloatArray, selected: Int): Float {
        val max = values.maxOrNull() ?: return 0f
        val sum = values.sumOf { kotlin.math.exp((it - max).toDouble()) }
        return if (sum <= 0.0) 0f else (kotlin.math.exp((values[selected] - max).toDouble()) / sum).toFloat()
    }

    private fun floatTensor(name: String, values: FloatArray, shape: LongArray): OnnxTensor {
        val buffer = floatBuffers.getOrPut(name) {
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        require(buffer.capacity() == values.size) { "Manga OCR input $name changed shape unexpectedly." }
        buffer.clear()
        buffer.put(values)
        buffer.rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    private fun longTensor(name: String, values: LongArray, shape: LongArray): OnnxTensor {
        val buffer = longBuffers.getOrPut(name) {
            ByteBuffer.allocateDirect(values.size * Long.SIZE_BYTES).order(ByteOrder.nativeOrder()).asLongBuffer()
        }
        require(buffer.capacity() == values.size) { "Manga OCR input $name changed shape unexpectedly." }
        buffer.clear()
        buffer.put(values)
        buffer.rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    override fun close() {
        floatBuffers.clear()
        longBuffers.clear()
        try {
            encoder.close()
        } finally {
            try {
                decoderInit.close()
            } finally {
                try {
                    decoderStep.close()
                } finally {
                    options.close()
                }
            }
        }
    }

    private data class DecoderState(
        val logits: Array<FloatArray>,
        val selfKey: FloatArray,
        val selfValue: FloatArray,
        val selfKeySlice: FloatArray,
        val selfValueSlice: FloatArray,
        val crossKey: FloatArray,
        val crossValue: FloatArray,
    )

    private companion object {
        const val IMAGE_SIDE = 224
        const val CHANNELS = 3
        const val ENCODER_SEQUENCE = 196
        const val HIDDEN_WIDTH = 256
        const val LAYERS = 4
        const val ATTENTION_HEADS = 4
        const val HEAD_WIDTH = 64
        const val CACHE_LENGTH = 256
        const val MAX_TOKENS = 48
        const val MAX_POSITION = 127
        const val VOCAB_SIZE = 9_415
        const val START_TOKEN = 2
        const val END_TOKEN = 3
        const val FIRST_TEXT_TOKEN = 5
        const val CACHE_SLICE_ELEMENTS = LAYERS * ATTENTION_HEADS * HEAD_WIDTH
        const val SELF_CACHE_ELEMENTS = LAYERS * ATTENTION_HEADS * CACHE_LENGTH * HEAD_WIDTH
        const val CROSS_CACHE_ELEMENTS = LAYERS * ATTENTION_HEADS * ENCODER_SEQUENCE * HEAD_WIDTH
        val SELF_CACHE_SHAPE = longArrayOf(LAYERS.toLong(), 1, ATTENTION_HEADS.toLong(), CACHE_LENGTH.toLong(), HEAD_WIDTH.toLong())
        val CROSS_CACHE_SHAPE = longArrayOf(LAYERS.toLong(), 1, ATTENTION_HEADS.toLong(), ENCODER_SEQUENCE.toLong(), HEAD_WIDTH.toLong())
        val WHITESPACE_REGEX = Regex("\\s+")
        const val ENCODER_INPUT = "serving_default_args_0:0"
        const val ENCODER_STATES = "encoder_hidden_states"
        const val INPUT_IDS = "input_ids"
        const val POSITION_IDS = "position_ids"
        const val SELF_KEY_CACHE = "self_k_cache"
        const val SELF_VALUE_CACHE = "self_v_cache"
        const val CROSS_KEY_CACHE = "cross_k_cache"
        const val CROSS_VALUE_CACHE = "cross_v_cache"
    }
}
