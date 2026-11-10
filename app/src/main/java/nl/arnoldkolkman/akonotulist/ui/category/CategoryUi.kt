package nl.arnoldkolkman.akonotulist.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.arnoldkolkman.akonotulist.category.CategoryRepository
import nl.arnoldkolkman.akonotulist.data.CategoryEntity
import nl.arnoldkolkman.akonotulist.ui.theme.CategoryColors
import nl.arnoldkolkman.akonotulist.ui.theme.categoryColor

/** Emoji offered in the editor; free input is allowed too. */
val CategoryEmoji = listOf(
    "💼", "🤝", "📋", "💡", "🏠", "🚗", "📞", "🎓", "🏗️", "💶",
    "⚖️", "🩺", "🛠️", "📈", "🎯", "🧾", "🗓️", "✈️", "❤️", "⭐",
)

/** Small coloured category label used in lists and the detail screen. */
@Composable
fun CategoryChip(
    category: CategoryEntity,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    byAi: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val color = categoryColor(category.colorIndex)
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(50),
        modifier = modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = if (small) 8.dp else 10.dp, vertical = if (small) 2.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (category.emoji.isNotBlank()) {
                Text(category.emoji, fontSize = if (small) 11.sp else 14.sp)
                Spacer(Modifier.width(4.dp))
            } else {
                Box(Modifier.size(if (small) 6.dp else 8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                category.name,
                style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (byAi) {
                Spacer(Modifier.width(3.dp))
                Icon(Icons.Filled.AutoAwesome, "Automatisch ingedeeld", Modifier.size(if (small) 10.dp else 13.dp))
            }
            if (onRemove != null) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.Close, "Categorie verwijderen", Modifier.size(15.dp).clickable(onClick = onRemove))
            }
        }
    }
}

/** Multi-select dialog to file a recording (or several) under categories. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPickerDialog(
    title: String,
    categories: List<CategoryEntity>,
    initiallySelected: Set<Long>,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (categories.isEmpty()) {
                    Text("Je hebt nog geen categorieën. Maak er eerst een aan.", style = MaterialTheme.typography.bodyMedium)
                }
                categories.forEach { c ->
                    val checked = c.id in selected
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .clickable { selected = if (checked) selected - c.id else selected + c.id }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        CategoryChip(c)
                    }
                }
                TextButton(onClick = onManage, modifier = Modifier.padding(top = 4.dp)) { Text("Categorieën beheren…") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Opslaan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

/** Create/edit dialog with live preview, emoji and colour palette and a description for the AI. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditDialog(
    initial: CategoryEntity?,
    error: String?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (CategoryEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(initial?.emoji ?: CategoryEmoji.first()) }
    var color by remember { mutableStateOf(initial?.colorIndex ?: 0) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val preview = (initial ?: CategoryEntity(name = "")).copy(name = name.ifBlank { "Voorbeeld" }, emoji = emoji, colorIndex = color)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nieuwe categorie" else "Categorie bewerken") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryChip(preview)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Naam") }, singleLine = true, isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Icoon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CategoryEmoji.forEach { e ->
                        val sel = e == emoji
                        Box(
                            Modifier.size(38.dp).clip(CircleShape)
                                .background(if (sel) categoryColor(color).copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 19.sp) }
                    }
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(if (emoji.isBlank()) categoryColor(color).copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { emoji = "" },
                        contentAlignment = Alignment.Center,
                    ) { Text("—", fontSize = 16.sp) }
                }
                Text("Kleur", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryColors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c)
                                .then(if (i == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = i },
                            contentAlignment = Alignment.Center,
                        ) { if (i == color) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
                OutlinedTextField(
                    value = description, onValueChange = { description = it.take(200) },
                    label = { Text("Omschrijving (optioneel)") },
                    supportingText = { Text("Wat hoort hierin? Helpt bij automatisch indelen.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Categorie verwijderen", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave((initial ?: CategoryEntity(name = "")).copy(name = name.trim(), emoji = emoji, colorIndex = color, description = description.trim()))
            }) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Categorie verwijderen?") },
            text = { Text("De categorie verdwijnt. Opnames, transcripten en samenvattingen blijven gewoon bewaard.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Verwijderen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") } },
        )
    }
}

/** Suggestions shown when the user has no categories yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySuggestions(onPick: (CategoryEntity) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryRepository.suggestions.forEach { s -> CategoryChip(s, onClick = { onPick(s) }) }
    }
}
