package com.spawnlau.gemmapocket.shared
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
internal actual fun decodePhoto(bytes: ByteArray): ImageBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
