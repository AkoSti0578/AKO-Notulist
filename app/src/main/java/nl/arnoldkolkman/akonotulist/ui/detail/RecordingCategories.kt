package nl.arnoldkolkman.akonotulist.ui.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.ui.category.CategoryChip
import nl.arnoldkolkman.akonotulist.ui.category.CategoryPickerDialog

/** The recording's categories as chips, with "+ Categorie" and "✨ Voorstellen" (AI). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingCategoriesRow(recordingId: Long, hasTranscript: Boolean, onManage: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.appContainer
    val scope = rememberCoroutineScope()
    val all by remember { c.db.categories().observeWithCounts() }.collectAsStateWithLifecycle(emptyList())
    val refs by remember(recordingId) { c.db.categories().observeRefsFor(recordingId) }.collectAsStateWithLifecycle(emptyList())
    var picking by remember { mutableStateOf(false) }
    var suggesting by remember { mutableStateOf(false) }
    val byId = all.associate { it.id to it.toEntity() }

    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        refs.mapNotNull { ref -> byId[ref.categoryId]?.let { it to ref.byAi } }.forEach { (cat, byAi) ->
            CategoryChip(cat, byAi = byAi, onRemove = { scope.launch { c.db.categories().unassign(recordingId, cat.id) } })
        }
        AssistChip(
            onClick = { if (all.isEmpty()) onManage() else picking = true },
            label = { Text(if (refs.isEmpty()) "Categorie" else "Wijzigen") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(18.dp)) },
        )
        if (all.isNotEmpty() && hasTranscript && c.secrets.hasApiKey()) {
            AssistChip(
                enabled = !suggesting,
                onClick = {
                    suggesting = true
                    scope.launch {
                        val result = runCatching { c.categories.autoCategorize(recordingId, onlyIfEmpty = false) }
                        suggesting = false
                        val msg = result.fold(
                            onSuccess = { ids ->
                                if (ids.isEmpty()) "Geen passende categorie gevonden"
                                else "Ingedeeld bij: " + ids.mapNotNull { byId[it]?.name }.joinToString(", ")
                            },
                            onFailure = { "Voorstellen mislukt: ${it.message}" },
                        )
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                },
                label = { Text("Voorstellen") },
                leadingIcon = {
                    if (suggesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                },
            )
        }
    }

    if (picking) {
        CategoryPickerDialog(
            title = "Categorieën",
            categories = all.map { it.toEntity() },
            initiallySelected = refs.mapTo(HashSet()) { it.categoryId },
            onDismiss = { picking = false },
            onManage = { picking = false; onManage() },
        ) { ids ->
            picking = false
            scope.launch { c.categories.setForRecording(recordingId, ids) }
        }
    }
}
