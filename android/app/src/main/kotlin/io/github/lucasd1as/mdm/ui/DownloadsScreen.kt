package io.github.lucasd1as.mdm.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lucasd1as.mdm.DownloadItem
import io.github.lucasd1as.mdm.DownloadsViewModel
import io.github.lucasd1as.mdm.Eta
import io.github.lucasd1as.mdm.Listing
import io.github.lucasd1as.mdm.R
import io.github.lucasd1as.mdm.Status
import io.github.lucasd1as.mdm.UrlText
import kotlinx.coroutines.delay

private enum class Filter { All, Unfinished, Finished }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    listing: Listing,
    incoming: String?,
    onIncomingHandled: () -> Unit,
    onSettings: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var prefill by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DownloadItem?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(incoming) {
        if (incoming != null) {
            prefill = incoming
            showAdd = true
            onIncomingHandled()
        }
    }

    val shown = listing.items
        .filter {
            when (filter) {
                Filter.All -> true
                Filter.Unfinished -> it.status != Status.COMPLETE
                Filter.Finished -> it.status == Status.COMPLETE
            }
        }
        .sortedByDescending { it.addedAt }
    val anyRunning = listing.items.any { it.isActive }
    val anyStopped = listing.items.any { it.status == Status.PAUSED || it.status == Status.FAILED }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (anyRunning) {
                        IconButton(onClick = viewModel::pauseAll) {
                            Icon(Icons.Default.Pause, stringResource(R.string.action_pause_all))
                        }
                    } else if (anyStopped) {
                        IconButton(onClick = viewModel::resumeAll) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.action_resume_all))
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_clear_finished)) },
                                onClick = { menu = false; viewModel.clearCompleted() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_settings)) },
                                onClick = { menu = false; onSettings() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { prefill = ""; showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (listing.items.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(filter == Filter.All, { filter = Filter.All }, { Text(stringResource(R.string.filter_all)) })
                    FilterChip(filter == Filter.Unfinished, { filter = Filter.Unfinished }, { Text(stringResource(R.string.filter_unfinished)) })
                    FilterChip(filter == Filter.Finished, { filter = Filter.Finished }, { Text(stringResource(R.string.filter_finished)) })
                }
            }
            if (listing.active > 0) {
                Text(
                    stringResource(
                        R.string.summary_fmt,
                        stringResource(R.string.speed_fmt, Formatter.formatShortFileSize(LocalContext.current, listing.speed)),
                        pluralStringResource(R.plurals.active_count, listing.active, listing.active),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (shown.isEmpty()) {
                EmptyState(filtered = listing.items.isNotEmpty())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { item ->
                        DownloadRow(
                            item = item,
                            onPause = { viewModel.pause(item) },
                            onResume = { viewModel.resume(item) },
                            onOpen = { viewModel.open(item) },
                            onCopy = { viewModel.copyLink(item) },
                            onDelete = { confirmDelete = item },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDownloadDialog(
            initial = prefill,
            viewModel = viewModel,
            onDismiss = { showAdd = false },
            onAdd = { urls, checksum -> showAdd = false; viewModel.add(urls, checksum) },
        )
    }
    confirmDelete?.let { item ->
        DeleteDialog(
            item = item,
            onDismiss = { confirmDelete = null },
            onConfirm = { withFile -> confirmDelete = null; viewModel.remove(item, withFile) },
        )
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (filtered) R.string.empty_filtered_title else R.string.empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!filtered) {
                Text(
                    stringResource(R.string.empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun iconFor(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> Icons.Default.Image
    "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp" -> Icons.Default.VideoFile
    "mp3", "flac", "wav", "m4a", "ogg", "opus", "aac" -> Icons.Default.AudioFile
    "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2", "iso" -> Icons.Default.FolderZip
    "apk", "xapk", "aab" -> Icons.Default.Android
    "pdf" -> Icons.Default.PictureAsPdf
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun statusLine(item: DownloadItem): String {
    val ctx = LocalContext.current
    fun size(b: Long) = Formatter.formatShortFileSize(ctx, b)
    val of = item.size?.let { stringResource(R.string.size_of_fmt, size(item.downloaded), size(it)) }
        ?: size(item.downloaded)
    return when (item.status) {
        Status.QUEUED -> stringResource(R.string.status_queued)
        Status.DOWNLOADING -> listOfNotNull(
            item.fraction?.let { "${(it * 100).toInt()}%" },
            of,
            if (item.speed > 0) stringResource(R.string.speed_fmt, size(item.speed)) else null,
            Eta.seconds(item)?.let { stringResource(R.string.eta_fmt, Eta.format(it)) },
        ).joinToString(" · ")
        Status.PAUSED -> stringResource(R.string.status_paused_fmt, of)
        Status.FAILED -> stringResource(R.string.status_failed_fmt, item.error ?: stringResource(R.string.error_unknown))
        Status.COMPLETE -> stringResource(
            R.string.status_complete_fmt,
            size(item.size ?: item.downloaded),
            when {
                item.inPlace -> item.path?.substringBeforeLast('/') ?: stringResource(R.string.saved_app)
                item.exported != null -> stringResource(R.string.saved_downloads)
                else -> stringResource(R.string.saved_app)
            },
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(iconFor(item.name), null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    statusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == Status.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.status != Status.COMPLETE && item.status != Status.FAILED) {
                    val f = item.fraction
                    if (f != null) {
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    } else if (item.status == Status.DOWNLOADING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            when (item.status) {
                Status.QUEUED, Status.DOWNLOADING -> IconButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, stringResource(R.string.action_pause))
                }
                Status.PAUSED -> IconButton(onClick = onResume) {
                    Icon(Icons.Default.PlayArrow, stringResource(R.string.action_resume))
                }
                Status.FAILED -> IconButton(onClick = onResume) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.action_retry))
                }
                Status.COMPLETE -> IconButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open))
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy_link)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menu = false; onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(item: DownloadItem, onDismiss: () -> Unit, onConfirm: (deleteFile: Boolean) -> Unit) {
    var withFile by remember { mutableStateOf(true) }
    val hasFile = item.status == Status.COMPLETE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title, item.name)) },
        text = {
            if (hasFile) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withFile, { withFile = it })
                    Text(stringResource(R.string.delete_with_file))
                }
            } else {
                Text(stringResource(R.string.delete_unfinished))
            }
        },
        confirmButton = { TextButton({ onConfirm(hasFile && withFile) }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AddDownloadDialog(
    initial: String,
    viewModel: DownloadsViewModel,
    onDismiss: () -> Unit,
    onAdd: (List<String>, String?) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var checksum by rememberSaveable { mutableStateOf("") }
    val urls = remember(text) { UrlText.all(text) }
    var info by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // One address: ask the server for its name and size once typing pauses.
    LaunchedEffect(urls) {
        info = null
        failed = false
        if (urls.size != 1) return@LaunchedEffect
        delay(600)
        val r = viewModel.probe(urls[0])
        r.onSuccess {
            info = if (it.size != null) "${it.name} · ${Formatter.formatShortFileSize(ctx, it.size)}" else it.name
        }.onFailure {
            failed = true
            info = it.message
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.add_hint)) },
                    minLines = 2,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(onClick = { viewModel.clipboardUrl()?.let { text = it } }) {
                            Icon(Icons.Default.ContentPaste, stringResource(R.string.action_paste))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (urls.size == 1) {
                    OutlinedTextField(
                        value = checksum,
                        onValueChange = { checksum = it },
                        label = { Text(stringResource(R.string.add_checksum_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val line = when {
                    text.isBlank() -> null
                    urls.isEmpty() -> stringResource(R.string.add_not_a_link)
                    urls.size > 1 -> pluralStringResource(R.plurals.add_links_found, urls.size, urls.size)
                    info != null -> info
                    else -> stringResource(R.string.add_checking)
                }
                if (line != null) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed || urls.isEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(urls, checksum.trim().ifEmpty { null }) }, enabled = urls.isNotEmpty()) {
                Text(stringResource(R.string.action_download))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
