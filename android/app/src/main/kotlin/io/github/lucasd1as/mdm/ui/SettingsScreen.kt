package io.github.lucasd1as.mdm.ui

import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.lucasd1as.mdm.StoragePaths
import kotlinx.coroutines.flow.Flow
import java.io.File
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lucasd1as.mdm.AppSettings
import io.github.lucasd1as.mdm.R

private val SPEED_STEPS = listOf(0L, 256_000L, 512_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    messages: Flow<String>,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            IntSetting(
                title = stringResource(R.string.setting_connections),
                help = stringResource(R.string.setting_connections_help),
                value = settings.maxConnections, range = 1..32,
                onCommit = { onChange(settings.copy(maxConnections = it)) },
            )
            IntSetting(
                title = stringResource(R.string.setting_active),
                help = stringResource(R.string.setting_active_help),
                value = settings.maxActive, range = 1..8,
                onCommit = { onChange(settings.copy(maxActive = it)) },
            )
            val stepIndex = SPEED_STEPS.indexOfFirst { it >= settings.speedLimit }.let { if (it < 0) SPEED_STEPS.lastIndex else it }
            IntSetting(
                title = stringResource(R.string.setting_speed),
                help = stringResource(R.string.setting_speed_help),
                value = stepIndex, range = 0..SPEED_STEPS.lastIndex,
                label = { i ->
                    if (SPEED_STEPS[i] == 0L) stringResource(R.string.setting_unlimited)
                    else stringResource(R.string.speed_fmt, Formatter.formatShortFileSize(ctx, SPEED_STEPS[i]))
                },
                onCommit = { onChange(settings.copy(speedLimit = SPEED_STEPS[it])) },
            )
            SaveFolderSetting(settings, onChange)
            HorizontalDivider()
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
            Text(stringResource(R.string.about_version, version ?: "?"), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openLink(ctx, "https://github.com/anefzaoui/misterdownloadmanager") }) {
                Text(stringResource(R.string.about_upstream))
            }
            TextButton(onClick = { openLink(ctx, "https://github.com/lucasd1as/MisterAndroidDownloader") }) {
                Text(stringResource(R.string.about_source))
            }
        }
    }
}

@Composable
private fun IntSetting(
    title: String,
    help: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
    label: @Composable (Int) -> String = { it.toString() },
) {
    // Dragging only moves this local copy; the value is saved when the finger lifts.
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(label(dragging.toInt()), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onCommit(dragging.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Where finished files go: copied to Downloads, or written straight into a chosen folder. */
@Composable
private fun SaveFolderSetting(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val ctx = LocalContext.current
    val direct = settings.saveDir.isNotEmpty()
    var allowed by remember { mutableStateOf(hasAllFilesAccess()) }
    // Back from the system's permission screen.
    LifecycleResumeEffect(Unit) {
        allowed = hasAllFilesAccess()
        onPauseOrDispose { }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { StoragePaths.fromTreeDocumentId(DocumentsContract.getTreeDocumentId(it)) }
        if (path != null) onChange(settings.copy(saveDir = path))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setting_direct), style = MaterialTheme.typography.titleSmall)
            }
            Switch(
                checked = direct,
                onCheckedChange = { on ->
                    if (!on) {
                        onChange(settings.copy(saveDir = ""))
                    } else {
                        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        onChange(settings.copy(saveDir = File(downloads, "ROMs").absolutePath))
                    }
                },
            )
        }
        Text(
            stringResource(R.string.setting_direct_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (direct) {
            Text(
                stringResource(R.string.setting_folder_saved, settings.saveDir),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(null) }) {
                    Text(stringResource(R.string.setting_choose_folder))
                }
                if (!allowed) {
                    Button(onClick = { requestAllFilesAccess(ctx) }) {
                        Text(stringResource(R.string.setting_grant_access))
                    }
                }
            }
            Text(
                stringResource(if (allowed) R.string.setting_access_ok else R.string.setting_access_needed),
                style = MaterialTheme.typography.bodySmall,
                color = if (allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

private fun requestAllFilesAccess(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
