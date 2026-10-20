package com.spawnlau.gemmapocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.spawnlau.gemmapocket.shared.GemmaApp

class MainActivity : ComponentActivity() {
    private lateinit var host: AndroidServices
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AndroidServices(this)
        setContent { GemmaApp(host, intent.getBooleanExtra("selfTest", false), Modifier.semantics { testTagsAsResourceId = true }) }
    }
    override fun onResume() { super.onResume(); if (::host.isInitialized) host.resumed() }
    override fun onDestroy() { if (::host.isInitialized) host.dispose(); super.onDestroy() }
}
