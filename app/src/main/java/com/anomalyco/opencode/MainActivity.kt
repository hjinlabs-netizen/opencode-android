package com.anomalyco.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.anomalyco.opencode.ui.navigation.OpenCodeNavGraph
import com.anomalyco.opencode.ui.theme.OpenCodeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. Edge-to-edge is enabled so the Compose theme owns
 * the system bars; all content lives in [OpenCodeNavGraph].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenCodeTheme {
                OpenCodeNavGraph()
            }
        }
    }
}
