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
import kotlin.math.min

/**
 * Copies a user-picked image into app-private storage, respects EXIF orientation,
 * center-crops it to a square, and keeps enough resolution for high-density screens.
 */
object ArtworkTransformer {

    private const val MAX_INPUT_BYTES = 24 * 1024 * 1024

    suspend fun import(context: Context, trackId: String, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    readLimited(it, MAX_INPUT_BYTES)
                }
            }.getOrNull() ?: return@withContext null
            if (bytes.isEmpty()) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sourceWidth = bounds.outWidth
            val sourceHeight = bounds.outHeight
            if (sourceWidth <= 0 || sourceHeight <= 0) return@withContext null

            var sampleSize = 1
            while (max(sourceWidth, sourceHeight) / (sampleSize * 2) >= 1_600) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: return@withContext null

            val recycled = mutableSetOf<Bitmap>()
            try {
                var current = applyExifRotation(decoded, bytes, recycled)
                val side = min(current.width, current.height)
                if (side <= 0) return@withContext null

                if (current.width != side || current.height != side) {
                    val cropped = Bitmap.createBitmap(
                        current,
                        (current.width - side) / 2,
                        (current.height - side) / 2,
                        side,
                        side
                    )
                    if (cropped !== current) {
                        current.recycle()
                        recycled.add(current)
                        current = cropped
                    }
                }

                if (current.width > 1_024) {
                    val scaled = Bitmap.createScaledBitmap(current, 1_024, 1_024, true)
                    if (scaled !== current) {
                        current.recycle()
                        recycled.add(current)
                        current = scaled
                    }
                }

                val outputBitmap = if (current.hasAlpha()) {
                    val composed = Bitmap.createBitmap(
                        current.width,
                        current.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(composed)
                    canvas.drawColor(Color.rgb(13, 10, 20))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(current, 0f, 0f, paint)
                    current.recycle()
                    recycled.add(current)
                    composed
                } else {
                    current
                }

                try {
                    val directory = File(context.filesDir, "artwork").apply { mkdirs() }
                    val fileName = "$trackId-custom-${System.currentTimeMillis()}.jpg"
                    val file = File(directory, fileName)
                    FileOutputStream(file).use { output ->
                        check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                            "无法写入封面"
                        }
                    }
                    fileName
                } finally {
                    if (!outputBitmap.isRecycled) outputBitmap.recycle()
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
