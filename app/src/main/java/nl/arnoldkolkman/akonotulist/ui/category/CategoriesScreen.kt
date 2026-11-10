package nl.arnoldkolkman.akonotulist.ui.category

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.category.CategoryRepository
import nl.arnoldkolkman.akonotulist.data.CategoryEntity
import nl.arnoldkolkman.akonotulist.ui.theme.categoryColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val c = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    val categories by remember { c.db.categories().observeWithCounts() }.collectAsStateWithLifecycle(null)
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(cat: CategoryEntity) = scope.launch {
        when (c.categories.save(cat)) {
            is CategoryRepository.SaveResult.Saved -> { editing = null; creating = false; error = null }
            CategoryRepository.SaveResult.NameTaken -> error = "Er bestaat al een categorie met deze naam"
            CategoryRepository.SaveResult.EmptyName -> error = "Geef de categorie een naam"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categorieën") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { error = null; creating = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Nieuwe categorie") },
            )
        },
    ) { padding ->
        val list = categories ?: return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Orden je opnames", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Maak categorieën zoals Werk of Klanten. Per categorie zie je daarna alle actiepunten, " +
                                "besluiten en samenvattingen bij elkaar. Nieuwe opnames kan de app automatisch indelen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Snel beginnen — tik om toe te voegen:", style = MaterialTheme.typography.labelLarge)
                        CategorySuggestions { s -> save(s) }
                    }
                }
            }
            itemsIndexed(list, key = { _, it -> it.id }) { i, cat ->
                val color = categoryColor(cat.colorIndex)
                Card(
                    onClick = { onOpen(cat.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 0.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(6.dp).height(72.dp).background(color))
                        Box(
                            Modifier.padding(start = 12.dp).size(44.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) { Text(cat.emoji.ifBlank { cat.name.take(1).uppercase() }, fontSize = 22.sp, color = color, fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(cat.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${cat.recordingCount} opname" + (if (cat.recordingCount == 1) "" else "s") +
                                    (if (cat.description.isNotBlank()) " · ${cat.description}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Column {
                            IconButton(onClick = { scope.launch { c.categories.move(cat.id, -1) } }, enabled = i > 0, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.KeyboardArrowUp, "Omhoog")
                            }
                            IconButton(onClick = { scope.launch { c.categories.move(cat.id, 1) } }, enabled = i < list.lastIndex, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, "Omlaag")
                            }
                        }
                        IconButton(onClick = { error = null; editing = cat.toEntity() }) { Icon(Icons.Filled.Edit, "Bewerken") }
                    }
                }
            }
            if (list.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tip: houd in de lijst met opnames een opname ingedrukt om er meerdere tegelijk in te delen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (creating) {
        CategoryEditDialog(initial = null, error = error, onDismiss = { creating = false }, onDelete = null) { save(it) }
    }
    editing?.let { cat ->
        CategoryEditDialog(
            initial = cat,
            error = error,
            onDismiss = { editing = null },
            onDelete = { scope.launch { c.categories.delete(cat.id); editing = null } },
        ) { save(it) }
    }
}
