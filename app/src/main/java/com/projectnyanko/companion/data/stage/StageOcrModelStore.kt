package com.projectnyanko.companion.data.stage

import android.content.Context
import com.projectnyanko.companion.domain.stage.StageLocale
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Copies only the selected language's ONNX weights into app-private storage and verifies hashes. */
class StageOcrModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "stage-ocr-models")
    private val copiedModels = mutableMapOf<String, File>()

    @Synchronized
    fun ensureModels(locale: StageLocale): Map<String, File> {
        val manifestBytes = appContext.assets.open(MANIFEST_PATH).use { it.readBytes() }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        val models = manifest.getJSONArray("models")
        require(root.isDirectory || root.mkdirs()) { "Could not create private ONNX model storage." }
        val prefixes = if (locale == StageLocale.JP) listOf(SHARED_PREFIX, "ja/") else listOf(SHARED_PREFIX, "en/")
        return buildMap {
            for (index in 0 until models.length()) {
                val record = models.getJSONObject(index)
                val assetPath = record.getString("asset")
                if (prefixes.none(assetPath::startsWith)) continue
                val expectedSize = record.getLong("size")
                val expectedHash = record.getString("sha256")
                val target = copiedModels[assetPath] ?: File(root, assetPath)
                val validExisting = target.isFile && target.length() == expectedSize && sha256(target) == expectedHash
                if (!validExisting) copyAsset(assetPath, target, expectedSize, expectedHash)
                copiedModels[assetPath] = target
                put(assetPath, target)
            }
            require(isNotEmpty()) { "No ONNX OCR models are listed for ${locale.name}." }
        }
    }

    private fun copyAsset(assetPath: String, target: File, expectedSize: Long, expectedHash: String) {
        val parent = target.parentFile ?: error("Invalid model asset path.")
        require(parent.isDirectory || parent.mkdirs()) { "Could not create ONNX model directory." }
        val temporary = File(parent, "${target.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            appContext.assets.open("ocr/$assetPath").use { input ->
                temporary.outputStream().buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(copied == expectedSize) { "OCR model $assetPath has an unexpected file size." }
            require(actualHash == expectedHash) { "OCR model $assetPath failed SHA-256 validation." }
            if (target.exists()) require(target.delete()) { "Could not replace old OCR model $assetPath." }
            require(temporary.renameTo(target)) { "Could not finish importing OCR model $assetPath." }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANIFEST_PATH = "ocr/source-manifest.json"

        /** The DB text detector is language independent, so every locale imports it. */
        const val SHARED_PREFIX = "shared/"
    }
}
