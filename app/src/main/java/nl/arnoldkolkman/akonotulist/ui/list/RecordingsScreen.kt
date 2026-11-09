package nl.arnoldkolkman.akonotulist.ui.list

import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.AppContainer
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.core.AppSettings
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.recording.RecorderState
import nl.arnoldkolkman.akonotulist.recording.StartResult
import nl.arnoldkolkman.akonotulist.ui.StatusChip
import nl.arnoldkolkman.akonotulist.ui.theme.Coral

class RecordingsViewModel(private val c: AppContainer) : ViewModel() {
    val recordings: StateFlow<List<RecordingEntity>> =
        c.db.recordings().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recorder: StateFlow<RecorderState> = c.recorder.state
    val settings: StateFlow<AppSettings?> =
        c.settings.settings.map { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun hasApiKey() = c.secrets.hasApiKey()
    fun start(): StartResult = c.recorder.start()
    fun stop() = c.recorder.stop()
    fun pause() = c.recorder.pause()
    fun resume() = c.recorder.resume()
    fun mark() = c.recorder.mark()
    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(keepScreenOn = on) } }
    }
    fun import(uris: List<Uri>, onError: (String) -> Unit) {
        viewModelScope.launch {
            uris.forEach { uri ->
                runCatching { c.importer.import(uri) }.onFailure { onError(it.message ?: "Importeren mislukt") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onOpen: (Long) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val vm: RecordingsViewModel = viewModel { RecordingsViewModel(ctx.appContainer) }
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            handleStart(vm.start(), ctx)
        } else {
            Toast.makeText(ctx, "Zonder microfoontoestemming kan AKO Notulist niet opnemen.", Toast.LENGTH_LONG).show()
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris) { msg -> scope.launch { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } }
    }

    fun requestStart() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AKO Notulist", fontWeight = FontWeight.SemiBold) },
                actions = {
                    val keepOn = settings?.keepScreenOn == true
                    IconButton(onClick = {
                        vm.setKeepScreenOn(!keepOn)
                        Toast.makeText(ctx, if (keepOn) "Scherm mag weer uitgaan" else "Scherm blijft aan", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            if (keepOn) Icons.Filled.LightMode else Icons.Outlined.LightMode,
                            if (keepOn) "Scherm aanhouden: aan" else "Scherm aanhouden: uit",
                            tint = if (keepOn) Coral else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Zoeken") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Instellingen") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                RecorderCard(
                    state = recorder,
                    onStart = ::requestStart,
                    onStop = vm::stop,
                    onPause = vm::pause,
                    onResume = vm::resume,
                    onMark = {
                        vm.mark()
                        Toast.makeText(ctx, "Moment gemarkeerd", Toast.LENGTH_SHORT).show()
                    },
                    onImport = { pickAudio.launch(arrayOf("audio/*", "video/mp4")) },
                )
            }
            if (settings != null && !vm.hasApiKey()) {
                item { ApiKeyBanner(onSettings) }
            }
            if (recordings.isEmpty()) {
                item {
                    Text(
                        "Nog geen opnames. Tik op de rode knop om te beginnen, of importeer een audiobestand.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp, start = 8.dp, end = 8.dp),
                    )
                }
            } else {
                item {
                    Text("Opnames", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                }
                items(recordings, key = { it.id }) { rec -> RecordingRow(rec) { onOpen(rec.id) } }
            }
        }
    }
}

private fun handleStart(result: StartResult, ctx: android.content.Context) {
    val msg = when (result) {
        StartResult.Started, StartResult.AlreadyRecording -> return
        StartResult.MissingPermission -> "Microfoontoestemming ontbreekt."
        is StartResult.Failed -> "Opname kon niet starten (${result.reason})."
    }
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

@Composable
private fun RecorderCard(
    state: RecorderState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMark: () -> Unit,
    onImport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = Format.duration(state.elapsedMs),
                fontSize = 44.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = when {
                    !state.active -> "Klaar om op te nemen"
                    state.paused -> "Gepauzeerd"
                    else -> "Opname loopt — ook met scherm uit"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))
            LevelMeter(level = if (state.active && !state.paused) state.level else 0f)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (state.active) {
                    FilledIconButton(
                        onClick = if (state.paused) onResume else onPause,
                        modifier = Modifier.size(56.dp),
                    ) { Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.paused) "Hervatten" else "Pauze") }
                }
                FilledIconButton(
                    onClick = if (state.active) onStop else onStart,
                    modifier = Modifier.size(84.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Coral, contentColor = Color.White),
                ) {
                    Icon(
                        if (state.active) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (state.active) "Stop opname" else "Start opname",
                        modifier = Modifier.size(40.dp),
                    )
                }
                if (state.active) {
                    FilledTonalIconButton(
                        onClick = onMark,
                        enabled = !state.paused,
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Filled.Star, "Markeer dit moment") }
                }
            }
            if (state.active && state.markers > 0) {
                Text(
                    "★ ${state.markers} " + if (state.markers == 1) "markering" else "markeringen",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            AnimatedVisibility(!state.active) {
                OutlinedButton(onClick = onImport, modifier = Modifier.padding(top = 16.dp)) {
                    Icon(Icons.Filled.FileUpload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Audiobestand importeren")
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    val animated by animateFloatAsState(level, animationSpec = tween(150), label = "level")
    val bars = 24
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
        repeat(bars) { i ->
            // Symmetric "VU" shape: bars in the centre react most.
            val centre = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / (bars / 2f)
            val h = (0.15f + animated * (0.35f + 0.65f * centre)).coerceIn(0.1f, 1f)
            Box(
                Modifier
                    .width(5.dp)
                    .height((28 * h).dp)
                    .clip(CircleShape)
                    .background(if (level > 0f) Coral.copy(alpha = 0.5f + 0.5f * centre) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            )
        }
    }
}

@Composable
private fun ApiKeyBanner(onSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, null)
            Spacer(Modifier.width(12.dp))
            Text(
                "Stel je OpenAI API-sleutel in om te kunnen transcriberen.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSettings) { Text("Instellen") }
        }
    }
}

@Composable
private fun RecordingRow(rec: RecordingEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rec.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(rec)
            }
            Spacer(Modifier.height(4.dp))
            val meta = buildList {
                add(Format.dateTime(rec.createdAt))
                if (rec.durationMs > 0) add(Format.duration(rec.durationMs))
                rec.estimatedCostUsd?.let { add("≈ " + Format.usd(it)) }
            }.joinToString("  ·  ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rec.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
        }
    }
}
