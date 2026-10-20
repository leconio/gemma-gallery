package com.spawnlau.gemmapocket

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

internal data class PreparedPhoto(val file: File, val width: Int, val height: Int)

/** Shared by the photo picker and the debug camera-original benchmark. */
internal object AndroidPhotoPreprocessor {
    fun prepare(resolver: ContentResolver, uri: Uri, destination: File): PreparedPhoto {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image" }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode image")
        val orientation = resolver.openInputStream(uri).use {
            ExifInterface(requireNotNull(it)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }
            8 -> setRotate(270f)
        } }
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val scale = minOf(1f, 1024f / maxOf(oriented.width, oriented.height))
        val bitmap = Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1), true)
        try {
            destination.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            return PreparedPhoto(destination, bitmap.width, bitmap.height)
        } catch (error: Exception) {
            destination.delete(); throw error
        } finally {
            if (bitmap !== oriented) bitmap.recycle()
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }
}
