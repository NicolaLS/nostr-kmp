package xyz.lilsus.nostr_kmp_demo_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.lilsus.nostr_kmp_demo_android.ui.NostrDemoScreen
import xyz.lilsus.nostr_kmp_demo_android.ui.theme.NostrkmpdemoandroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NostrkmpdemoandroidTheme {
                val viewModel: NostrDemoViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                NostrDemoScreen(
                    uiState = uiState,
                    onReconnect = viewModel::reconnect,
                    onGenerateIdentity = viewModel::createIdentity,
                    onPublishEvent = viewModel::publishEvent,
                    onDraftChanged = viewModel::updateDraft
                )
            }
        }
    }
}
