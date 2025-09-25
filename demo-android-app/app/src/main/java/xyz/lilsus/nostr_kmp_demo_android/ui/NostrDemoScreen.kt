package xyz.lilsus.nostr_kmp_demo_android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.lilsus.nostr_kmp_demo_android.DisplayEvent
import xyz.lilsus.nostr_kmp_demo_android.NostrDemoUiState
import xyz.lilsus.nostr_kmp_demo_android.R
import xyz.lilsus.nostr_kmp_demo_android.ui.theme.NostrkmpdemoandroidTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NostrDemoScreen(
    uiState: NostrDemoUiState,
    onReconnect: () -> Unit,
    onGenerateIdentity: () -> Unit,
    onPublishEvent: () -> Unit,
    onDraftChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.app_name)) },
                actions = {
                    TextButton(onClick = onReconnect) {
                        Text(text = "Reconnect")
                    }
                }
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Relay: ${uiState.relay}", style = MaterialTheme.typography.labelLarge)
            Text(text = "Status: ${uiState.status}", style = MaterialTheme.typography.bodyMedium)
            uiState.notice?.let {
                NoticeCard(message = it)
            }
            uiState.error?.let {
                ErrorCard(message = it)
            }
            HorizontalDivider()
            IdentitySection(
                publicKey = uiState.identityPublicKey,
                onGenerateIdentity = onGenerateIdentity
            )
            HorizontalDivider()
            EventComposer(
                draftContent = uiState.draftContent,
                onDraftChanged = onDraftChanged,
                onPublishEvent = onPublishEvent
            )
            HorizontalDivider()
            EventSection(
                title = "My Events",
                events = uiState.ownEvents,
                emptyMessage = "No events for this identity yet",
                minHeight = 220.dp
            )
            HorizontalDivider()
            EventSection(
                title = "All Events",
                events = uiState.events,
                emptyMessage = "Waiting for events…",
                minHeight = 220.dp
            )
        }
    }
}

@Composable
private fun IdentitySection(publicKey: String?, onGenerateIdentity: () -> Unit) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Identity",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = publicKey?.let { "Public key: $it" } ?: "No identity generated yet",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onGenerateIdentity) {
                Text(text = "New Identity")
            }
        }
    }
}

@Composable
private fun EventComposer(
    draftContent: String,
    onDraftChanged: (String) -> Unit,
    onPublishEvent: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Create Event",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = draftContent,
                onValueChange = onDraftChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Content") }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onPublishEvent, enabled = draftContent.isNotBlank()) {
                    Text(text = "Publish Event")
                }
            }
        }
    }
}

@Composable
private fun EventSection(
    title: String,
    events: List<DisplayEvent>,
    emptyMessage: String,
    minHeight: Dp
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                events.forEach { event ->
                    EventCard(event)
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: DisplayEvent) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = event.formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = event.content,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Author: ${event.authorShort}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun NoticeCard(message: String) {
    Card {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewNostrDemoScreen() {
    val sampleEvents = listOf(
        DisplayEvent(
            id = "1",
            author = "abcdef0123456789",
            content = "Hello from Nostr!",
            timestamp = 1_700_000_000,
            formattedTime = "12:34:56"
        ),
        DisplayEvent(
            id = "2",
            author = "fedcba9876543210",
            content = "Another message",
            timestamp = 1_700_000_100,
            formattedTime = "12:35:10"
        )
    )
    NostrkmpdemoandroidTheme {
        NostrDemoScreen(
            uiState = NostrDemoUiState(
                relay = "wss://relay.example",
                status = "Connected",
                events = sampleEvents,
                ownEvents = sampleEvents.take(1),
                notice = "Sample notice",
                identityPublicKey = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                draftContent = "Hello there"
            ),
            onReconnect = {},
            onGenerateIdentity = {},
            onPublishEvent = {},
            onDraftChanged = {},
            modifier = Modifier
        )
    }
}
