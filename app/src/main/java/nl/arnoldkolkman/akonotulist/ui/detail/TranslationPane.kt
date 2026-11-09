package nl.arnoldkolkman.akonotulist.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.data.TranslationEntity
import nl.arnoldkolkman.akonotulist.summary.TranslateWorker
import nl.arnoldkolkman.akonotulist.summary.TranslationLanguage
import nl.arnoldkolkman.akonotulist.summary.decodeList
import nl.arnoldkolkman.akonotulist.ui.theme.speakerColor

/** Plain-text version of a translation, for copying and sharing. */
internal fun translationAsText(
    title: String,
    lang: TranslationLanguage,
    summaryText: String?,
    segments: List<SegmentEntity>,
    texts: List<String>,
    names: Map<String, String>,
): String = buildString {
    if (summaryText.isNullOrBlank()) appendLine(title).appendLine() else appendLine(summaryText.trim()).appendLine()
    appendLine(
        when (lang) {
            TranslationLanguage.EN -> "Transcript"
            TranslationLanguage.DE -> "Transkript"
            TranslationLanguage.FR -> "Transcription"
        }
    )
    segments.forEachIndexed { i, s ->
        append('[').append(Format.timestamp(s.startMs)).append("] ")
        s.speaker?.let { append(names[it] ?: "Spreker $it").append(": ") }
        appendLine(texts.getOrNull(i) ?: s.text)
    }
}.trimEnd()

@Composable
fun TranslationPane(
    recordingId: Long,
    title: String,
    segments: List<SegmentEntity>,
    names: Map<String, String>,
    translations: List<TranslationEntity>,
    hasKey: Boolean,
    onSeek: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    var langCode by rememberSaveable { mutableStateOf(TranslationLanguage.EN.code) }
    val lang = TranslationLanguage.byCode(langCode) ?: TranslationLanguage.EN
    val work by remember(recordingId, lang) {
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(TranslateWorker.workName(recordingId, lang))
    }.collectAsStateWithLifecycle(emptyList())
    val info = work.lastOrNull()
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val failed = info?.state == WorkInfo.State.FAILED
    val translation = translations.firstOrNull { it.language == lang.code }
    val texts = remember(translation) { translation?.let { decodeList(it.segmentsJson) }.orEmpty() }
    val stale = translation != null && translation.segmentCount != segments.size

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "langs") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationLanguage.entries.forEach { l ->
                    FilterChip(
                        selected = l == lang,
                        onClick = { langCode = l.code },
                        label = { Text(l.label) },
                        leadingIcon = if (translations.any { it.language == l.code }) {
                            { Text("✓") }
                        } else null,
                    )
                }
            }
        }
        item(key = "state") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    running -> {
                        val p = info?.progress?.getInt(TranslateWorker.KEY_PROGRESS, 0) ?: 0
                        Text("Bezig met vertalen naar het ${lang.label}…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    translation == null -> {
                        if (failed) {
                            Text(
                                "Vertalen mislukt: " + (info?.outputData?.getString(TranslateWorker.KEY_ERROR) ?: "onbekende fout"),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(enabled = hasKey, onClick = { TranslateWorker.enqueue(ctx, recordingId, lang) }) {
                            Icon(Icons.Filled.Translate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Vertaal naar het ${lang.label}")
                        }
                        Text(
                            "Transcript en samenvatting worden vertaald met het chatmodel uit Instellingen. " +
                                "Kosten: meestal minder dan een cent per opname.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        val full = translationAsText(title, lang, translation.summaryText, segments, texts, names)
                        if (stale) {
                            Text(
                                "Het transcript is na het vertalen gewijzigd; vertaal opnieuw voor een actuele versie.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                ctx.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Vertaling", full))
                                Toast.makeText(ctx, "Vertaling gekopieerd", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Kopiëren")
                            }
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, "$title (${lang.label})")
                                    .putExtra(Intent.EXTRA_TEXT, full)
                                ctx.startActivity(Intent.createChooser(send, "Vertaling delen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) {
                                Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Delen")
                            }
                        }
                        TextButton(onClick = { TranslateWorker.enqueue(ctx, recordingId, lang) }) { Text("Opnieuw vertalen") }
                    }
                }
            }
        }
        if (translation != null && !running) {
            translation.summaryText?.takeIf { it.isNotBlank() }?.let { sum ->
                item(key = "summary") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        SelectionContainer { Text(sum, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
            itemsIndexed(segments, key = { _, s -> "t${s.id}" }) { i, seg ->
                Column(Modifier.fillMaxWidth().clickable { onSeek(seg.startMs) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row {
                        seg.speaker?.let {
                            Text(names[it] ?: "Spreker $it", color = speakerColor(it), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            Format.timestamp(seg.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(texts.getOrNull(i) ?: seg.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
