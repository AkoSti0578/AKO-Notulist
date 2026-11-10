package nl.arnoldkolkman.akonotulist.ui.category

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.category.ActionRow
import nl.arnoldkolkman.akonotulist.category.CategoryInsights
import nl.arnoldkolkman.akonotulist.category.CategoryRepository
import nl.arnoldkolkman.akonotulist.category.CategoryStats
import nl.arnoldkolkman.akonotulist.category.DecisionRow
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.SummaryEntity
import nl.arnoldkolkman.akonotulist.export.ExportFormat
import nl.arnoldkolkman.akonotulist.ui.StatusChip
import nl.arnoldkolkman.akonotulist.ui.theme.categoryColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDashboardScreen(categoryId: Long, onBack: () -> Unit, onOpenRecording: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.appContainer
    val scope = rememberCoroutineScope()
    val category by remember(categoryId) { c.db.categories().observe(categoryId) }.collectAsStateWithLifecycle(null)
    val recordings by remember(categoryId) { c.db.categories().observeRecordings(categoryId) }.collectAsStateWithLifecycle(emptyList())
    val summaries by remember(categoryId) { c.db.categories().observeSummaries(categoryId) }.collectAsStateWithLifecycle(emptyList())
    val statuses by remember { c.db.actionItems().observeAll() }.collectAsStateWithLifecycle(emptyList())

    val actions = remember(recordings, summaries, statuses) { CategoryInsights.actions(recordings, summaries, statuses) }
    val decisions = remember(recordings, summaries) { CategoryInsights.decisions(recordings, summaries) }
    val stats = remember(recordings, actions, decisions) { CategoryInsights.stats(recordings, actions, decisions) }

    var tab by rememberSaveable { mutableStateOf(0) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val cat = category
    val color = categoryColor(cat?.colorIndex ?: 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cat?.let { CategoryInsights.label(it) } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } },
                actions = {
                    Box {
                        IconButton(onClick = { shareMenu = true }, enabled = recordings.isNotEmpty()) { Icon(Icons.Filled.Share, "Rapport delen") }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            ExportFormat.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("Rapport als ${f.label}") },
                                    onClick = { shareMenu = false; scope.launch { c.categories.shareReport(ctx, categoryId, f) } },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { error = null; editing = true }, enabled = cat != null) { Icon(Icons.Filled.Edit, "Bewerken") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = color.copy(alpha = 0.10f)),
            )
        },
    ) { padding ->
        if (cat == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "hero") { Hero(cat.emoji, cat.description, color, stats) }
            item(key = "tabs") {
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp, containerColor = Color.Transparent) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Actiepunten" + if (stats.openActions > 0) " (${stats.openActions})" else "") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Besluiten") })
                    Tab(tab == 2, { tab = 2 }, text = { Text("Opnames (${stats.recordings})") })
                    Tab(tab == 3, { tab = 3 }, text = { Text("Samenvattingen") })
                }
            }
            when (tab) {
                0 -> {
                    item(key = "done_toggle") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showDone, onClick = { showDone = false }, label = { Text("Open (${stats.openActions})") })
                            FilterChip(selected = showDone, onClick = { showDone = true }, label = { Text("Alles (${actions.size})") })
                        }
                    }
                    val shown = if (showDone) actions else actions.filter { !it.done }
                    if (shown.isEmpty()) {
                        item(key = "no_actions") {
                            Empty(
                                if (actions.isEmpty()) "Nog geen actiepunten. Ze verschijnen hier zodra opnames in deze categorie een samenvatting hebben."
                                else "Alles is afgerond. 🎉"
                            )
                        }
                    }
                    items(shown, key = { "a${it.recordingId}_${it.index}" }) { row ->
                        ActionItemRow(row, color, onOpen = { onOpenRecording(row.recordingId) }) { done ->
                            scope.launch { c.categories.setActionDone(row.recordingId, row.index, row.text, done) }
                        }
                    }
                }
                1 -> {
                    if (decisions.isEmpty()) item(key = "no_dec") { Empty("Nog geen besluiten vastgelegd in deze categorie.") }
                    decisions.groupBy { it.recordingId }.forEach { (rid, rows) ->
                        item(key = "d$rid") { DecisionGroup(rows, color) { onOpenRecording(rid) } }
                    }
                }
                2 -> {
                    if (recordings.isEmpty()) item(key = "no_rec") { Empty("Nog geen opnames in deze categorie. Deel ze in via het detailscherm of houd een opname in de lijst ingedrukt.") }
                    items(recordings, key = { "r${it.id}" }) { r -> RecordingLine(r) { onOpenRecording(r.id) } }
                }
                else -> {
                    val sums = summaries.associateBy { it.recordingId }
                    val withSummary = recordings.filter { sums[it.id] != null }
                    if (withSummary.isEmpty()) item(key = "no_sum") { Empty("Nog geen samenvattingen in deze categorie.") }
                    items(withSummary, key = { "s${it.id}" }) { r -> SummaryCard(r, sums.getValue(r.id), color) { onOpenRecording(r.id) } }
                }
            }
        }
    }

    if (editing && cat != null) {
        CategoryEditDialog(
            initial = cat,
            error = error,
            onDismiss = { editing = false },
            onDelete = { scope.launch { c.categories.delete(cat.id); editing = false; onBack() } },
        ) { updated ->
            scope.launch {
                when (c.categories.save(updated)) {
                    is CategoryRepository.SaveResult.Saved -> editing = false
                    CategoryRepository.SaveResult.NameTaken -> error = "Er bestaat al een categorie met deze naam"
                    CategoryRepository.SaveResult.EmptyName -> error = "Geef de categorie een naam"
                }
            }
        }
    }
}

@Composable
private fun Hero(emoji: String, description: String, color: Color, s: CategoryStats) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.10f), Color.Transparent)))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji.isNotBlank()) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 30.sp) }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
                if (s.firstAt != null && s.lastAt != null) {
                    Text(
                        if (s.firstAt == s.lastAt) Format.dateTime(s.firstAt) else "${Format.dateTime(s.firstAt)} – ${Format.dateTime(s.lastAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Opnames", s.recordings.toString(), color, Modifier.weight(1f))
            StatTile("Duur", Format.duration(s.totalDurationMs), color, Modifier.weight(1f))
            StatTile("Open acties", s.openActions.toString(), color, Modifier.weight(1f))
            StatTile("Kosten", if (s.totalCostUsd > 0) Format.usd(s.totalCostUsd) else "–", color, Modifier.weight(1f))
        }
        val total = s.openActions + s.doneActions
        if (total > 0) {
            val progress by animateFloatAsState(s.doneActions.toFloat() / total, label = "progress")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${s.doneActions} van $total actiepunten afgerond",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { progress },
                    color = color,
                    trackColor = color.copy(alpha = 0.18f),
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun ActionItemRow(row: ActionRow, color: Color, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!row.done) }.padding(horizontal = 8.dp, vertical = 2.dp).animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.done, onCheckedChange = { onToggle(it) })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                row.text,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (row.done) TextDecoration.LineThrough else null,
                color = if (row.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${row.recordingTitle} · ${Format.dateTime(row.recordingCreatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onOpen).padding(vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DecisionGroup(rows: List<DecisionRow>, color: Color, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${rows.first().recordingTitle} · ${Format.dateTime(rows.first().recordingCreatedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
            rows.forEach { Text("•  ${it.text}", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun RecordingLine(r: RecordingEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Format.dateTime(r.createdAt), Format.duration(r.durationMs).takeIf { r.durationMs > 0 }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(r)
    }
}

@Composable
private fun SummaryCard(r: RecordingEntity, s: SummaryEntity, color: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall)
                Text(Format.dateTime(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}
