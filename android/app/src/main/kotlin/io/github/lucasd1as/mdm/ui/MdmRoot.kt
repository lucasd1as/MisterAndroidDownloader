package io.github.lucasd1as.mdm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucasd1as.mdm.DownloadsViewModel
import io.github.lucasd1as.mdm.R

private enum class Screen { Downloads, Settings }

@Composable
fun MdmRoot(
    viewModel: DownloadsViewModel,
    incoming: String?,
    onIncomingHandled: () -> Unit,
    initError: String?,
) {
    if (initError != null) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(24.dp).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.error_native_title), style = MaterialTheme.typography.titleLarge)
                Text(initError, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        return
    }
    var screen by rememberSaveable { mutableStateOf(Screen.Downloads) }
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    BackHandler(enabled = screen != Screen.Downloads) { screen = Screen.Downloads }
    when (screen) {
        Screen.Downloads -> DownloadsScreen(
            viewModel = viewModel,
            listing = listing,
            incoming = incoming,
            onIncomingHandled = onIncomingHandled,
            onSettings = { screen = Screen.Settings },
        )
        Screen.Settings -> SettingsScreen(
            settings = listing.settings,
            messages = viewModel.messages,
            onChange = viewModel::setSettings,
            onBack = { screen = Screen.Downloads },
        )
    }
}
