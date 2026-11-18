package com.example.patchnotify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.File

/** 参考图解码/缩放/EXIF 转正 + 小缓存；推理线程和缩略图共用 */
object ReferenceImageLoader {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(context: Context, uriString: String, maxSide: Int = 384): Bitmap? {
        if (uriString.isBlank() || maxSide <= 0) return null
        val key = "$uriString@$maxSide"
        cache.get(key)?.let { if (!it.isRecycled) return it }
        return try {
            val bytes = readBytes(context, uriString) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide ||
                   bounds.outHeight / (sample * 2) >= maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            bmp = applyExif(bytes, bmp)
            cache.put(key, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBytes(context: Context, uriString: String): ByteArray? {
        val uri = Uri.parse(uriString)
        return when {
            uri.scheme == "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            uri.scheme == "file" -> uri.path?.let { File(it).takeIf(File::exists)?.readBytes() }
            else -> File(uriString).takeIf(File::exists)?.readBytes()
        }
    }

    private fun applyExif(bytes: ByteArray, src: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> return src
            }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            out
        } catch (_: Throwable) {
            src
        }
    }
}
