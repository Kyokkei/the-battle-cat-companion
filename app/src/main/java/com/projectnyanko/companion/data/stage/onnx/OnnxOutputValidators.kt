package com.projectnyanko.companion.data.stage.onnx

import java.io.IOException

/** Converts ONNX Runtime's Java reflection arrays with dimension checks and no unchecked casts. */
internal object OnnxOutputValidators {
    fun batchTimeRows(value: Any, label: String): Array<FloatArray> {
        val batch = value as? Array<*> ?: throw IOException("$label output is not a tensor batch.")
        if (batch.size != 1) throw IOException("$label output batch size mismatch.")
        val rows = batch[0] as? Array<*> ?: throw IOException("$label output time dimension mismatch.")
        return Array(rows.size) { index ->
            rows[index] as? FloatArray ?: throw IOException("$label output row $index is not float data.")
        }
    }

    fun batchLogits(value: Any, label: String): Array<FloatArray> {
        val batch = value as? Array<*> ?: throw IOException("$label output is not a logits batch.")
        if (batch.size != 1) throw IOException("$label output batch size mismatch.")
        val row = batch[0] as? FloatArray ?: throw IOException("$label output logits row mismatch.")
        return arrayOf(row)
    }

    fun flattenCache(value: Any, expectedElements: Int, label: String): FloatArray {
        val flattened = FloatArray(expectedElements)
        var count = 0
        fun visit(element: Any?) {
            when (element) {
                is Array<*> -> element.forEach(::visit)
                is FloatArray -> element.forEach { item ->
                    if (count >= flattened.size) throw IOException("$label output is too large.")
                    flattened[count++] = item
                }
                else -> throw IOException("$label output dimensions mismatch.")
            }
        }
        visit(value)
        if (count != expectedElements) throw IOException("$label output element count mismatch.")
        return flattened
    }
}
