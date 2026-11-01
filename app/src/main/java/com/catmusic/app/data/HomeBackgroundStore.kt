package com.catmusic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * Copies a user-picked landscape/portrait photo into app-private storage for
 * the DIY home background. The source can be deleted afterwards; CatMusic
 * never keeps a persistent URI permission or modifies the original.
 */
object HomeBackgroundStore {

    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val MAX_DIMENSION = 2_000

    suspend fun import(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    readLimited(it, MAX_INPUT_BYTES)
                }
            }.getOrNull() ?: return@withContext null
            if (bytes.isEmpty()) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return@withContext null

            var sample = 1
            while (max(width, height) / (sample * 2) >= MAX_DIMENSION) {
                sample *= 2
            }
            val decoded = runCatching {
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }.getOrNull() ?: return@withContext null

            val recycled = mutableSetOf<Bitmap>()
            try {
                var current = applyExifRotation(decoded, bytes, recycled)
                val longest = max(current.width, current.height)
                if (longest > MAX_DIMENSION) {
                    val scale = MAX_DIMENSION.toFloat() / longest.toFloat()
                    val scaled = Bitmap.createScaledBitmap(
                        current,
                        (current.width * scale).toInt().coerceAtLeast(1),
                        (current.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (scaled !== current) {
                        current.recycle()
                        recycled.add(current)
                        current = scaled
                    }
                }

                val output = if (current.hasAlpha()) {
                    val composed = Bitmap.createBitmap(
                        current.width,
                        current.height,
                        Bitmap.Config.ARGB_8888
                    )
                    Canvas(composed).apply {
                        drawColor(Color.rgb(8, 7, 12))
                        drawBitmap(
                            current,
                            0f,
                            0f,
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        )
                    }
                    current.recycle()
                    recycled.add(current)
                    composed
                } else {
                    current
                }

                try {
                    val directory = File(context.filesDir, "backgrounds").apply { mkdirs() }
                    val name = "home-${System.currentTimeMillis()}.jpg"
                    FileOutputStream(File(directory, name)).use { stream ->
                        check(output.compress(Bitmap.CompressFormat.JPEG, 91, stream)) {
                            "无法写入背景图片"
                        }
                    }
                    name
                } finally {
                    if (!output.isRecycled) output.recycle()
                }
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            } finally {
                recycled.forEach { bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }

    fun file(context: Context, name: String?): File? {
        if (name.isNullOrBlank()) return null
        return File(File(context.filesDir, "backgrounds"), name)
            .takeIf { it.isFile }
    }

    fun delete(context: Context, name: String?) {
        if (name.isNullOrBlank()) return
        runCatching { File(File(context.filesDir, "backgrounds"), name).delete() }
    }

    private fun applyExifRotation(
        bitmap: Bitmap,
        bytes: ByteArray,
        recycled: MutableSet<Bitmap>
    ): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }

        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { transformed ->
                    if (transformed !== bitmap) {
                        bitmap.recycle()
                        recycled.add(bitmap)
                    }
                }
        }.getOrDefault(bitmap)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IllegalArgumentException("图片文件过大")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
