package nl.arnoldkolkman.akonotulist.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.ui.StatusChip
import nl.arnoldkolkman.akonotulist.ui.statusLabel
import nl.arnoldkolkman.akonotulist.ui.theme.speakerColor
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(recordingId: Long, initialSeekMs: Long?, onBack: () -> Unit) {
    val c = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    val rec by remember(recordingId) { c.db.recordings().observe(recordingId) }.collectAsStateWithLifecycle(null)
    val segments by remember(recordingId) { c.db.segments().observe(recordingId) }.collectAsStateWithLifecycle(emptyList())
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val r = rec
    val player = rememberAudioPlayer(r?.audioPath)
    LaunchedEffect(player, initialSeekMs) { if (player != null && initialSeekMs != null) player.seekTo(initialSeekMs, play = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Naam wijzigen") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Verwijderen") }
                },
            )
        },
    ) { padding ->
        if (r == null) return@Scaffold
        val listState = rememberLazyListState()
        val activeIndex = player?.let { p -> segments.indexOfLast { it.startMs <= p.positionMs } } ?: -1

        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(r)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOfNotNull(Format.dateTime(r.createdAt), Format.duration(r.durationMs).takeIf { r.durationMs > 0 }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(statusLabel(r), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                r.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (r.status.isBusy && r.status != RecordingStatus.IMPORTING) {
                    LinearProgressIndicator(
                        progress = { r.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                TranscribeActions(r, hasKey = c.secrets.hasApiKey(),
                    onTranscribe = { fresh -> scope.launch { c.transcription.enqueue(r.id, fresh) } },
                    onCancel = {
                        c.transcription.cancel(r.id)
                        scope.launch { c.db.recordings().setStatus(r.id, RecordingStatus.RECORDED) }
                    },
                )
                if (r.timestampsEstimated && segments.isNotEmpty()) {
                    Text(
                        "Tijden zijn geschat (dit model geeft geen tijdstempels).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (player != null) PlayerBar(player) else if (r.audioDeletedAt != null) {
                Text(
                    "Audio verwijderd volgens bewaarbeleid; de tekst is bewaard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
                itemsIndexed(segments, key = { _, s -> s.id }) { i, seg ->
                    SegmentRow(seg, speakerName = seg.speaker?.let { "Spreker $it" }, active = i == activeIndex) {
                        player?.seekTo(seg.startMs)
                    }
                }
            }
        }
    }

    if (renaming && r != null) {
        var text by remember { mutableStateOf(r.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Naam wijzigen") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { c.db.recordings().rename(r.id, text.trim().ifEmpty { r.title }) }
                    renaming = false
                }) { Text("Opslaan") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annuleren") } },
        )
    }
    if (confirmDelete && r != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Opname verwijderen?") },
            text = { Text("De audio, het transcript en de samenvatting worden definitief verwijderd.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        r.audioPath?.let { File(it).delete() }
                        c.db.recordings().delete(r.id)
                        onBack()
                    }
                }) { Text("Verwijderen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
        )
    }
}

@Composable
fun PlayerBar(p: AudioPlayerState) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Slider(
                value = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { f -> p.seekTo((f * p.durationMs).toLong(), play = p.isPlaying) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.timestamp(p.positionMs), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { p.skip(-10_000) }) { Icon(Icons.Filled.Replay10, "10 seconden terug") }
                FilledIconButton(onClick = { p.toggle() }) {
                    Icon(if (p.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.isPlaying) "Pauze" else "Afspelen")
                }
                IconButton(onClick = { p.skip(10_000) }) { Icon(Icons.Filled.Forward10, "10 seconden vooruit") }
                TextButton(onClick = { p.cycleSpeed() }) { Text("${p.speed}×".replace(".0×", "×")) }
                Spacer(Modifier.weight(1f))
                Text(Format.timestamp(p.durationMs), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SegmentRow(seg: SegmentEntity, speakerName: String?, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent
    Column(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (speakerName != null) {
                Text(speakerName, color = speakerColor(seg.speaker), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
            }
            Text(Format.timestamp(seg.startMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Text(seg.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TranscribeActions(
    r: RecordingEntity,
    hasKey: Boolean,
    onTranscribe: (fresh: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val canStart = r.audioPath != null && hasKey
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        when (r.status) {
            RecordingStatus.RECORDED -> Button(onClick = { onTranscribe(true) }, enabled = canStart) { Text("Transcriberen") }
            RecordingStatus.FAILED -> if (r.audioPath != null) {
                Button(onClick = { onTranscribe(false) }, enabled = canStart) { Text("Opnieuw proberen") }
            }
            RecordingStatus.QUEUED, RecordingStatus.TRANSCRIBING -> OutlinedButton(onClick = onCancel) { Text("Annuleren") }
            RecordingStatus.DONE -> if (r.audioPath != null) {
                OutlinedButton(onClick = { onTranscribe(true) }, enabled = canStart) { Text("Opnieuw transcriberen") }
            }
            else -> Unit
        }
    }
    if (!hasKey && (r.status == RecordingStatus.RECORDED || r.status == RecordingStatus.FAILED)) {
        Text("Stel eerst je API-sleutel in bij Instellingen.", style = MaterialTheme.typography.bodySmall)
    }
}
