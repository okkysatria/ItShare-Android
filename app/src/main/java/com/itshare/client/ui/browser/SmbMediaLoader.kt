package com.itshare.client.ui.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.core.smb.SmbClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

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
        val cacheKey = "${payload?.host}/${payload?.share}/${item.path}:${item.sizeBytes}:${item.lastModifiedEpoch}"
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

    val bytes = runCatching {
        streamResult.getOrThrow().use { it.readBytes() }
    }.getOrNull() ?: return null

    if (bytes.isEmpty()) return null

    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w("ItShareMedia", "Unreadable bounds for thumbnail: ${item.path}")
            return null
        }

        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        fixOrientation(rawBitmap, bytes)
    } catch (e: Exception) {
        Log.e("ItShareMedia", "Error decoding thumbnail for ${item.path}", e)
        null
    }
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
            val scaled = Bitmap.createScaledBitmap(
                frame,
                (frame.width * ratio).toInt().coerceAtLeast(1),
                (frame.height * ratio).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != frame) frame.recycle()
            scaled
        } else {
            frame
        }
    } catch (e: Exception) {
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

        val bytes = runCatching {
            streamResult.getOrThrow().use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        if (bytes.isEmpty()) return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w("ItShareMedia", "Cannot decode image bounds for ${item.path}")
            return@withContext null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2560)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null
        fixOrientation(rawBitmap, bytes)
    } catch (error: Exception) {
        Log.e("ItShareMedia", "Full image preview failed for ${item.path}", error)
        null
    }
}

private fun fixOrientation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
    val orientation = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
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
