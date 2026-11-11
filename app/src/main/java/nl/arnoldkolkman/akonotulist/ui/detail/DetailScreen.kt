package nl.arnoldkolkman.akonotulist.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.style.TextDecoration
import nl.arnoldkolkman.akonotulist.data.ActionItemStatus
import androidx.compose.material3.CircularProgressIndicator
import nl.arnoldkolkman.akonotulist.summary.Translator
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.InputChip
import nl.arnoldkolkman.akonotulist.data.MarkerEntity
import nl.arnoldkolkman.akonotulist.ui.theme.Coral
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.AppContainer
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.data.SpeakerEntity
import nl.arnoldkolkman.akonotulist.data.SummaryEntity
import nl.arnoldkolkman.akonotulist.export.ExportActions
import nl.arnoldkolkman.akonotulist.export.ExportFormat
import nl.arnoldkolkman.akonotulist.summary.PostProcessWorker
import nl.arnoldkolkman.akonotulist.summary.decodeList
import nl.arnoldkolkman.akonotulist.ui.StatusChip
import nl.arnoldkolkman.akonotulist.ui.statusLabel
import nl.arnoldkolkman.akonotulist.ui.theme.speakerColor
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(recordingId: Long, initialSeekMs: Long?, onBack: () -> Unit, onManageCategories: () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = ctx.appContainer
    val scope = rememberCoroutineScope()
    val export = remember { ExportActions(ctx, c) }
    val rec by remember(recordingId) { c.db.recordings().observe(recordingId) }.collectAsStateWithLifecycle(null)
    val segments by remember(recordingId) { c.db.segments().observe(recordingId) }.collectAsStateWithLifecycle(emptyList())
    val speakers by remember(recordingId) { c.db.speakers().observe(recordingId) }.collectAsStateWithLifecycle(emptyList())
    val summary by remember(recordingId) { c.db.summaries().observe(recordingId) }.collectAsStateWithLifecycle(null)
    val markers by remember(recordingId) { c.db.markers().observe(recordingId) }.collectAsStateWithLifecycle(emptyList())
    val translations by remember(recordingId) { c.db.translations().observe(recordingId) }.collectAsStateWithLifecycle(emptyList())
    val actionStatuses by remember(recordingId) { c.db.actionItems().observe(recordingId) }.collectAsStateWithLifecycle(emptyList())
    var askResummarize by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editSpeakers by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var followPlayback by remember { mutableStateOf(true) }

    val r = rec
    val names = speakers.associate { it.label to it.displayName }
    val player = rememberAudioPlayer(r?.audioPath)
    LaunchedEffect(player, initialSeekMs) { if (player != null && initialSeekMs != null) player.seekTo(initialSeekMs, play = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } },
                actions = {
                    if (speakers.isNotEmpty()) IconButton(onClick = { editSpeakers = true }) { Icon(Icons.Filled.People, "Sprekers") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Meer") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (segments.isNotEmpty()) {
                                ExportFormat.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = { Text("Delen als ${f.label}") },
                                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                                        onClick = { menuOpen = false; scope.launch { export.share(recordingId, f) } },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Kopiëren naar klembord") },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            if (export.copyToClipboard(recordingId)) Toast.makeText(ctx, "Gekopieerd", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                            DropdownMenuItem(text = { Text("Naam wijzigen") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menuOpen = false; renaming = true })
                            DropdownMenuItem(
                                text = { Text("Verwerkingslog") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, null) },
                                onClick = { menuOpen = false; showLog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Verwijderen", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (r == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            Header(r, segments.isNotEmpty(), c.secrets.hasApiKey(),
                onTranscribe = { fresh -> scope.launch { c.transcription.enqueue(r.id, fresh) } },
                onCancel = {
                    c.transcription.cancel(r.id)
                    scope.launch { c.db.recordings().setStatus(r.id, RecordingStatus.RECORDED) }
                },
            )
            if (r.status == RecordingStatus.QUEUED || r.status == RecordingStatus.TRANSCRIBING || r.status == RecordingStatus.SUMMARIZING) {
                BackgroundWorkHint(onShowLog = { showLog = true })
            }
            RecordingCategoriesRow(r.id, hasTranscript = segments.isNotEmpty(), onManage = onManageCategories)
            if (player != null) PlayerBar(player) else if (r.audioDeletedAt != null) {
                Text(
                    "Audio verwijderd volgens het bewaarbeleid; de tekst is bewaard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (markers.isNotEmpty()) {
                MarkerBar(
                    markers = markers,
                    onSeek = { m -> tab = 0; player?.seekTo(m.timeMs) },
                    onDelete = { m -> scope.launch { c.db.markers().delete(m.id) } },
                )
            }
            if (segments.isNotEmpty()) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Transcript") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Samenvatting") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Vertaling") })
                }
            }
            when {
                segments.isEmpty() -> Unit
                tab == 0 -> TranscriptList(
                    segments, names, speakers, player, followPlayback,
                    showNameHint = speakers.isNotEmpty() && speakers.any { it.displayName == "Spreker ${it.label}" },
                    onNameSpeakers = { editSpeakers = true },
                    markedSegmentIds = markedSegments(segments, markers),
                    onFollowChange = { followPlayback = it },
                    onReassign = { seg, label -> scope.launch { reassign(c, seg, label) } },
                )
                tab == 1 -> SummaryPane(
                    summary, r,
                    statuses = actionStatuses,
                    onToggleAction = { i, text, done -> scope.launch { c.categories.setActionDone(r.id, i, text, done) } },
                    onGenerate = { PostProcessWorker.enqueue(ctx, r.id, forceSummary = true) },
                )
                else -> TranslationPane(
                    recordingId = r.id,
                    title = r.title,
                    segments = segments,
                    names = names,
                    translations = translations,
                    hasKey = c.secrets.hasApiKey(),
                    onSeek = { ms -> player?.seekTo(ms) },
                )
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
    if (editSpeakers) {
        SpeakersDialog(recordingId, speakers, onDismiss = { editSpeakers = false }) { updated ->
            val changed = updated.any { u -> speakers.firstOrNull { it.label == u.label }?.displayName != u.displayName }
            scope.launch { updated.forEach { c.db.speakers().upsert(it) } }
            editSpeakers = false
            // The summary was written with the old names: offer to regenerate it.
            if (changed && summary != null) askResummarize = true
        }
    }
    if (showLog) ProcessingLogDialog(recordingId) { showLog = false }
    if (askResummarize && r != null) {
        AlertDialog(
            onDismissRequest = { askResummarize = false },
            title = { Text("Samenvatting bijwerken?") },
            text = { Text("De samenvatting gebruikt nog de oude sprekernamen. Wil je een nieuwe samenvatting met de nieuwe namen?") },
            confirmButton = {
                TextButton(onClick = {
                    askResummarize = false
                    PostProcessWorker.enqueue(ctx, r.id, forceSummary = true)
                }) { Text("Bijwerken") }
            },
            dismissButton = { TextButton(onClick = { askResummarize = false }) { Text("Nee") } },
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
                        c.transcription.cancel(r.id)
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

private suspend fun reassign(c: AppContainer, seg: SegmentEntity, label: String) {
    val all = c.db.segments().forRecording(seg.recordingId)
    c.db.segments().replace(seg.recordingId, all.map { if (it.id == seg.id) it.copy(id = 0, speaker = label) else it.copy(id = 0) })
    if (c.db.speakers().forRecording(seg.recordingId).none { it.label == label }) {
        c.db.speakers().upsert(SpeakerEntity(seg.recordingId, label, "Spreker $label"))
    }
}

@Composable
private fun Header(
    r: RecordingEntity,
    hasTranscript: Boolean,
    hasKey: Boolean,
    onTranscribe: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(r)
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(
                    Format.dateTime(r.createdAt),
                    Format.duration(r.durationMs).takeIf { r.durationMs > 0 },
                    r.estimatedCostUsd?.let { "≈ " + Format.usd(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (r.status != RecordingStatus.DONE) {
            Text(statusLabel(r), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        r.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (r.status.isBusy && r.status != RecordingStatus.IMPORTING) {
            LinearProgressIndicator(progress = { r.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        val canStart = r.audioPath != null && hasKey
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            when (r.status) {
                RecordingStatus.RECORDED -> Button(onClick = { onTranscribe(true) }, enabled = canStart) { Text("Transcriberen") }
                RecordingStatus.FAILED -> if (r.audioPath != null) Button(onClick = { onTranscribe(false) }, enabled = canStart) { Text("Opnieuw proberen") }
                RecordingStatus.QUEUED, RecordingStatus.TRANSCRIBING -> OutlinedButton(onClick = onCancel) { Text("Annuleren") }
                RecordingStatus.DONE -> if (r.audioPath != null && !hasTranscript) {
                    OutlinedButton(onClick = { onTranscribe(true) }, enabled = canStart) { Text("Opnieuw transcriberen") }
                }
                else -> Unit
            }
        }
        if (!hasKey && (r.status == RecordingStatus.RECORDED || r.status == RecordingStatus.FAILED)) {
            Text("Stel eerst je API-sleutel in bij Instellingen.", style = MaterialTheme.typography.bodySmall)
        }
        if (r.timestampsEstimated && hasTranscript) {
            Text(
                "Tijden zijn geschat: dit model geeft geen tijdstempels.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranscriptList(
    segments: List<SegmentEntity>,
    names: Map<String, String>,
    speakers: List<SpeakerEntity>,
    player: AudioPlayerState?,
    follow: Boolean,
    markedSegmentIds: Set<Long>,
    showNameHint: Boolean,
    onNameSpeakers: () -> Unit,
    onFollowChange: (Boolean) -> Unit,
    onReassign: (SegmentEntity, String) -> Unit,
) {
    val listState = rememberLazyListState()
    val activeIndex = player?.let { p -> segments.indexOfLast { it.startMs <= p.positionMs } } ?: -1
    LaunchedEffect(activeIndex, follow, player?.isPlaying) {
        if (follow && player?.isPlaying == true && activeIndex >= 0) listState.animateScrollToItem(maxOf(0, activeIndex - 1))
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (showNameHint) {
                item(key = "name_hint") {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.People, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Wie is wie? Geef de sprekers een naam.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = onNameSpeakers) { Text("Benoemen") }
                        }
                    }
                }
            }
            itemsIndexed(segments, key = { _, s -> s.id }) { i, seg ->
                SegmentRow(
                    seg = seg,
                    speakerName = seg.speaker?.let { names[it] ?: "Spreker $it" },
                    active = i == activeIndex,
                    starred = seg.id in markedSegmentIds,
                    speakers = speakers,
                    onReassign = { onReassign(seg, it) },
                ) { player?.seekTo(seg.startMs) }
            }
        }
        if (player != null) {
            SmallFloatingActionButton(
                onClick = { onFollowChange(!follow) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = if (follow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ) { Icon(Icons.Filled.MyLocation, if (follow) "Meescrollen uit" else "Meescrollen aan") }
        }
    }
}

@Composable
fun PlayerBar(p: AudioPlayerState) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
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
fun SegmentRow(
    seg: SegmentEntity,
    speakerName: String?,
    active: Boolean,
    starred: Boolean = false,
    speakers: List<SpeakerEntity> = emptyList(),
    onReassign: (String) -> Unit = {},
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (speakerName != null) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { menu = true }.padding(end = 4.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(speakerColor(seg.speaker)))
                        Spacer(Modifier.width(6.dp))
                        Text(speakerName, color = speakerColor(seg.speaker), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Text("Deze zin toewijzen aan", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        val next = nextLabel(speakers.map { it.label })
                        (speakers.map { it.label to it.displayName } + (next to "Nieuwe spreker ($next)")).forEach { (label, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; onReassign(label) })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            if (starred) {
                Icon(Icons.Filled.Star, "Gemarkeerd", tint = Coral, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                Format.timestamp(seg.startMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(seg.text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun nextLabel(used: List<String>): String {
    var i = 0
    while (true) {
        val l = if (i < 26) ('A' + i).toString() else "S${i + 1}"
        if (l !in used) return l
        i++
    }
}

@Composable
private fun SummaryPane(
    summary: SummaryEntity?,
    r: RecordingEntity,
    statuses: List<ActionItemStatus>,
    onToggleAction: (index: Int, text: String, done: Boolean) -> Unit,
    onGenerate: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (summary == null) {
            Text(
                if (r.status == RecordingStatus.SUMMARIZING) "Samenvatting wordt gemaakt…" else "Nog geen samenvatting.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (r.status != RecordingStatus.SUMMARIZING) {
                Button(onClick = onGenerate) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Samenvatting maken")
                }
            }
            return@Column
        }
        val actions = decodeList(summary.actionItemsJson)
        val decisions = decodeList(summary.decisionsJson)
        // Long-press to select any part; the button below copies everything.
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Samenvatting", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(summary.summary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (actions.isNotEmpty()) {
            val done = statuses.filter { it.done }.associate { it.itemIndex to it.text }
            Text(
                "Actiepunten (${actions.indices.count { done[it] == actions[it] }}/${actions.size} afgerond)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            actions.forEachIndexed { i, text ->
                val isDone = done[i] == text
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onToggleAction(i, text, !isDone) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = isDone, onCheckedChange = { onToggleAction(i, text, it) })
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (isDone) TextDecoration.LineThrough else null,
                        color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (decisions.isNotEmpty()) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Besluiten", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    decisions.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        val ctx = LocalContext.current
        FilledTonalButton(onClick = {
            val text = summaryAsText(r.title, summary.summary, actions, decisions)
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Samenvatting", text))
            Toast.makeText(ctx, "Samenvatting gekopieerd", Toast.LENGTH_SHORT).show()
        }) {
            Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Samenvatting kopiëren")
        }
        Text(
            "Gemaakt met ${summary.model} op ${Format.dateTime(summary.createdAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onGenerate, enabled = r.status != RecordingStatus.SUMMARIZING) { Text("Opnieuw samenvatten") }
    }
}

/** Segment that was being spoken at each marker (the last segment starting at or before it). */
internal fun markedSegments(segments: List<SegmentEntity>, markers: List<MarkerEntity>): Set<Long> =
    markers.mapNotNull { m -> segments.lastOrNull { it.startMs <= m.timeMs }?.id }.toSet()

@Composable
private fun MarkerBar(markers: List<MarkerEntity>, onSeek: (MarkerEntity) -> Unit, onDelete: (MarkerEntity) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        items(markers, key = { it.id }) { m ->
            InputChip(
                selected = false,
                onClick = { onSeek(m) },
                label = { Text(Format.timestamp(m.timeMs)) },
                leadingIcon = { Icon(Icons.Filled.Star, null, tint = Coral, modifier = Modifier.size(16.dp)) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close, "Markering verwijderen",
                        modifier = Modifier.size(16.dp).clickable { onDelete(m) },
                    )
                },
            )
        }
    }
}

internal fun summaryAsText(title: String, summary: String, actions: List<String>, decisions: List<String>): String = buildString {
    appendLine(title)
    appendLine()
    appendLine("Samenvatting")
    appendLine(summary.trim())
    if (actions.isNotEmpty()) {
        appendLine()
        appendLine("Actiepunten")
        actions.forEach { appendLine("- $it") }
    }
    if (decisions.isNotEmpty()) {
        appendLine()
        appendLine("Besluiten")
        decisions.forEach { appendLine("- $it") }
    }
}.trimEnd()

@Composable
private fun SpeakersDialog(
    recordingId: Long,
    speakers: List<SpeakerEntity>,
    onDismiss: () -> Unit,
    onSave: (List<SpeakerEntity>) -> Unit,
) {
    val ctx = LocalContext.current
    val c = ctx.appContainer
    val scope = rememberCoroutineScope()
    val edits = remember { mutableStateMapOf<String, String>().apply { speakers.forEach { put(it.label, it.displayName) } } }
    var suggesting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sprekers benoemen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Tip: tik in het transcript op een zin om te horen wie er spreekt. Twee sprekers dezelfde naam " +
                        "geven voegt ze samen in de weergave en export.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    enabled = !suggesting && c.secrets.hasApiKey(),
                    onClick = {
                        suggesting = true
                        scope.launch {
                            val result = runCatching { Translator(c).suggestNames(recordingId) }
                            suggesting = false
                            result.onSuccess { names ->
                                names.forEach { (label, name) -> edits[label] = name }
                                Toast.makeText(
                                    ctx,
                                    if (names.isEmpty()) "Geen namen gevonden in het gesprek" else "${names.size} naam/namen voorgesteld — controleer ze",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }.onFailure { Toast.makeText(ctx, "Voorstellen mislukt: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                ) {
                    if (suggesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Namen voorstellen uit gesprek")
                }
                speakers.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(speakerColor(s.label)))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = edits[s.label].orEmpty(),
                            onValueChange = { edits[s.label] = it },
                            label = { Text("Spreker ${s.label}") },
                            singleLine = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(speakers.map { it.copy(displayName = edits[it.label]?.trim().orEmpty().ifEmpty { "Spreker ${it.label}" }) })
            }) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}
