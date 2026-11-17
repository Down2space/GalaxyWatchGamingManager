package com.gwgm.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gwgm.manager.ui.AppRoot
import com.gwgm.manager.ui.theme.GwgmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GwgmTheme { AppRoot() } }
    }
}
