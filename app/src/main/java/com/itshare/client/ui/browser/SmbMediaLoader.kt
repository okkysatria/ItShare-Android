package com.itshare.client.ui.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.core.smb.SmbClientManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val thumbnailCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

internal fun SmbFileItem.isImageFile(): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
    )

internal fun SmbFileItem.isVideoFile(): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "mp4", "m4v", "3gp", "webm", "mkv", "mov", "avi", "flv", "wmv", "ts"
    )

internal suspend fun loadSmbThumbnail(
    smb: SmbClientManager,
    item: SmbFileItem,
    maxDimensionPx: Int
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val payload = smb.getActivePayload()
        val cacheKey = "${payload?.host}:${payload?.port}/${payload?.share}/${item.path}:${item.sizeBytes}:${item.lastModifiedEpoch}:$maxDimensionPx"
        thumbnailCache.get(cacheKey)?.let { return@withContext it }

        val thumbnail: Bitmap? = if (item.isImageFile()) {
            decodeImageThumbnail(smb, item, maxDimensionPx)
        } else if (item.isVideoFile()) {
            decodeVideoThumbnail(smb, item, maxDimensionPx)
        } else null

        if (thumbnail != null) {
            thumbnailCache.put(cacheKey, thumbnail)
        }
        thumbnail
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.e("ItShareMedia", "Thumbnail load failed for ${item.path}", error)
        null
    }
}

private suspend fun decodeImageThumbnail(
    smb: SmbClientManager,
    item: SmbFileItem,
    maxDimensionPx: Int
): Bitmap? {
    val streamResult = smb.openReadStream(item.path)
    if (streamResult.isFailure) return null

    return decodeRemoteImage(item, streamResult.getOrThrow(), maxDimensionPx)
}

private suspend fun decodeVideoThumbnail(
    smb: SmbClientManager,
    item: SmbFileItem,
    maxDimensionPx: Int
): Bitmap? {
    val sourceResult = smb.openMediaDataSource(item.path, item.sizeBytes)
    if (sourceResult.isFailure) return null

    val source = sourceResult.getOrThrow()
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(source)

        val frame = if (Build.VERSION.SDK_INT >= 27) {
            runCatching {
                retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxDimensionPx, maxDimensionPx)
            }.getOrNull() ?: runCatching {
                retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxDimensionPx, maxDimensionPx)
            }.getOrNull() ?: runCatching {
                retriever.frameAtTime
            }.getOrNull()
        } else {
            runCatching {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: runCatching {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: runCatching {
                retriever.frameAtTime
            }.getOrNull()
        }

        if (frame != null && maxOf(frame.width, frame.height) > maxDimensionPx) {
            val ratio = maxDimensionPx.toFloat() / maxOf(frame.width, frame.height)
            val scaled = frame.scale(
                (frame.width * ratio).toInt().coerceAtLeast(1),
                (frame.height * ratio).toInt().coerceAtLeast(1),
                filter = true
            )
            if (scaled != frame) frame.recycle()
            scaled
        } else {
            frame
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e("ItShareMedia", "Video thumbnail extraction failed for ${item.path}", e)
        null
    } finally {
        runCatching { retriever.release() }
        runCatching { source.close() }
    }
}

internal suspend fun loadSmbFullImage(smb: SmbClientManager, item: SmbFileItem): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val streamResult = smb.openReadStream(item.path)
        if (streamResult.isFailure) return@withContext null
        decodeRemoteImage(item, streamResult.getOrThrow(), 2560)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.e("ItShareMedia", "Full image preview failed for ${item.path}", error)
        null
    }
}

private fun decodeRemoteImage(
    item: SmbFileItem,
    input: java.io.InputStream,
    maxDimensionPx: Int
): Bitmap? {
    val tempFile = File.createTempFile("itshare_image_", ".cache")
    try {
        input.use { source ->
            FileOutputStream(tempFile).use { target -> source.copyTo(target, 128 * 1024) }
        }
        if (tempFile.length() == 0L) return null
        // Use the bytes actually received as the source of truth. SMB directory
        // metadata may be stale while a file is being replaced or uploaded; rejecting
        // a fully decodable image because its old listing size differs breaks preview.

        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(tempFile)) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    val scale = maxDimensionPx.toFloat() / maxOf(width, height).coerceAtLeast(1)
                    val targetWidth = (width * minOf(scale, 1f)).toInt().coerceAtLeast(1)
                    val targetHeight = (height * minOf(scale, 1f)).toInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetWidth, targetHeight)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()?.let { return it }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w("ItShareMedia", "Unreadable image bounds: ${item.path}")
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, options) ?: return null
        return fixOrientation(bitmap, tempFile)
    } finally {
        tempFile.delete()
    }
}

private fun fixOrientation(bitmap: Bitmap, file: File): Bitmap {
    val orientation = runCatching {
        val exif = ExifInterface(file.absolutePath)
        exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val rotationDegrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    if (rotationDegrees == 0f) return bitmap

    val matrix = Matrix().apply { postRotate(rotationDegrees) }
    return try {
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        rotated
    } catch (_: Throwable) {
        bitmap
    }
}

private fun sampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    while (
        maxOf(width / sample, height / sample) > target ||
        (width.toLong() / sample) * (height.toLong() / sample) > 16_000_000L
    ) sample *= 2
    return sample
}
