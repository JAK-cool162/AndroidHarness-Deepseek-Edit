package com.androidharness.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Keeps an app-owned, bounded copy; the chosen photo and system wallpaper are untouched. */
class HarnessCanvasStore(private val context: Context, private val settings: SettingsRepository) {
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) { lock.withLock {
        val root = directory(context).apply { mkdirs() }
        val source = File.createTempFile("import-", ".image", context.cacheDir)
        var output: File? = null
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                source.outputStream().use { stream ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32L * 1024 * 1024) { "Image is too large" }
                        stream.write(buffer, 0, count)
                    }
                }
            }
            val bitmap = decode(source)
            try {
                val sample = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                val seed = try {
                    val pixels = IntArray(96 * 96)
                    sample.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                    val opaque = pixels.filter { (it ushr 24) >= 128 }.map { it or (0xFF shl 24) }.toIntArray()
                    Score.score(if (opaque.isEmpty()) emptyMap() else QuantizerCelebi.quantize(opaque, 64), desired = 1, filter = false).first().toLong() and 0xFFFFFFFF
                } finally { if (sample !== bitmap) sample.recycle() }
                val alpha = bitmap.hasAlpha()
                val animated = animatedExtension(source)
                val id = "canvas-${UUID.randomUUID()}.${animated ?: if (alpha) "png" else "jpg"}"
                val saved = File(root, id)
                output = saved
                // Animated images keep their original bytes so every frame plays; stills are re-encoded at a bounded size.
                if (animated != null) source.copyTo(saved, overwrite = true)
                else saved.outputStream().use { check(bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)) }
                withContext(NonCancellable) {
                    settings.updateHarnessCanvas { it.copy(imageId = id, seed = seed, enabled = true) }
                    output = null
                    root.listFiles()?.filter { it.name != id && validId.matches(it.name) }?.forEach { it.delete() }
                }
            } finally { bitmap.recycle() }
        } finally { source.delete(); output?.delete() }
    } }

    suspend fun remove() = withContext(Dispatchers.IO) { lock.withLock {
        settings.updateHarnessCanvas { it.copy(imageId = null, seed = null, adaptColors = false) }
        directory(context).listFiles()?.filter { validId.matches(it.name) }?.forEach { it.delete() }
    } }

    private fun decode(source: File): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            require(info.size.width > 0 && info.size.height > 0)
            val scale = minOf(1.0, 1920.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to read image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1920) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        val matrix = Matrix()
        when (ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** @return "gif" or "webp" for an animated image Android 9+ can play, or null to store a still. */
    private fun animatedExtension(source: File): String? {
        if (Build.VERSION.SDK_INT < 28 || source.length() > MAX_ANIMATED_BYTES) return null
        val head = ByteArray(minOf(source.length(), 4096L).toInt())
        source.inputStream().use { input -> var read = 0; while (read < head.size) { val n = input.read(head, read, head.size - read); if (n < 0) break; read += n } }
        val ascii = String(head, Charsets.ISO_8859_1)
        return when {
            ascii.startsWith("GIF8") -> "gif"
            ascii.startsWith("RIFF") && ascii.startsWith("WEBP", 8) && ascii.contains("ANIM") -> "webp"
            else -> null
        }
    }

    companion object {
        /** Animated backgrounds are played from the original file, so they get a tighter limit than stills. */
        private const val MAX_ANIMATED_BYTES = 16L * 1024 * 1024
        private val lock = Mutex()
        private val validId = canvasImageIdPattern
        fun directory(context: Context) = File(context.filesDir, "harness-canvas")
        fun image(context: Context, id: String?): File? = id?.takeIf(validId::matches)
            ?.let { File(directory(context), it) }?.takeIf { it.isFile }
    }
}
