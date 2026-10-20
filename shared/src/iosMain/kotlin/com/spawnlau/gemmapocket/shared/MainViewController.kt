package com.spawnlau.gemmapocket.shared
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ComposeUIViewController
import org.jetbrains.skia.Image
import platform.UIKit.UIViewController
fun MainViewController(host: PlatformServices, autoSmoke: Boolean): UIViewController = ComposeUIViewController { GemmaApp(host, autoSmoke) }
internal actual fun decodePhoto(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
