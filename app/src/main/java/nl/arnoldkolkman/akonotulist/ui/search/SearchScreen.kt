package nl.arnoldkolkman.akonotulist.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.SearchHit

/** Builds a safe FTS4 MATCH expression: every word must match as prefix. */
object FtsQuery {
    fun from(input: String): String? {
        val tokens = input.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    fun tokens(input: String): List<String> =
        input.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (Long, Long) -> Unit) {
    val c = LocalContext.current.appContainer
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        delay(250) // debounce
        val match = FtsQuery.from(query)
        hits = if (match == null) emptyList() else runCatching { c.db.segments().search(match) }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Zoek in alle transcripten") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (query.isNotBlank() && hits.isEmpty()) {
                item { Text("Geen resultaten", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(hits, key = { it.segmentId }) { hit ->
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(hit.recordingId, hit.startMs) }.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        "${hit.title} · ${Format.timestamp(hit.startMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(highlight(hit.text, FtsQuery.tokens(query)), style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                    Text(Format.dateTime(hit.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun highlight(text: String, tokens: List<String>): AnnotatedString {
    val style = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.tertiaryContainer)
    return buildAnnotatedString {
        append(text)
        val lower = text.lowercase()
        tokens.forEach { t ->
            var i = lower.indexOf(t)
            while (i >= 0) {
                addStyle(style, i, i + t.length)
                i = lower.indexOf(t, i + t.length)
            }
        }
    }
}
