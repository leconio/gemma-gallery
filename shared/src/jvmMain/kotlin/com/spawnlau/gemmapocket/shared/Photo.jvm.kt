package com.spawnlau.gemmapocket.shared
import androidx.compose.ui.graphics.ImageBitmap
internal actual fun decodePhoto(bytes: ByteArray): ImageBitmap = error("Image decoding is not used by headless shared-logic tests")
