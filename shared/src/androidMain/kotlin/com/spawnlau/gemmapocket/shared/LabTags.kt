package com.spawnlau.gemmapocket.shared
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
internal actual fun labResourceTags(): Modifier = Modifier.semantics { testTagsAsResourceId = true }
