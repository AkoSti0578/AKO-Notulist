package nl.arnoldkolkman.akonotulist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.BuildConfig
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.core.AppSettings
import nl.arnoldkolkman.akonotulist.transcribe.TranscriptionModels

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(t) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instellingen") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ApiKeySection()

            Section("Transcriptie") {
                Text("Model", style = MaterialTheme.typography.labelLarge)
                TranscriptionModels.all.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = s.transcriptionModel == m.id, role = Role.RadioButton) { update { it.copy(transcriptionModel = m.id) } }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = s.transcriptionModel == m.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(m.label + if (m == TranscriptionModels.DEFAULT) " (standaard)" else "", style = MaterialTheme.typography.bodyLarge)
                            Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                TextSetting("Taal (ISO-639-1, bijv. nl)", s.language) { v -> update { it.copy(language = v.trim().lowercase().take(5)) } }
                TextSetting("Woordenlijst: namen en vaktermen, komma-gescheiden", s.vocabulary, singleLine = false) { v ->
                    update { it.copy(vocabulary = v) }
                }
                SwitchSetting("Automatisch transcriberen na stoppen", "Start de upload zodra een opname of import klaar is.", s.autoTranscribe) { v ->
                    update { it.copy(autoTranscribe = v) }
                }
                SwitchSetting(
                    "Sprekers schatten",
                    "Voor modellen zonder sprekerherkenning: laat het chatmodel per zin inschatten wie er spreekt.",
                    s.estimateSpeakers,
                ) { v -> update { it.copy(estimateSpeakers = v) } }
            }

            Section("Samenvatting") {
                SwitchSetting("Samenvatting maken", "Samenvatting, actiepunten en besluiten in het Nederlands.", s.summaryEnabled) { v ->
                    update { it.copy(summaryEnabled = v) }
                }
                TextSetting("Chatmodel", s.summaryModel) { v -> update { it.copy(summaryModel = v.trim()) } }
            }

            Section("Achtergrondverwerking") {
                BatteryOptimizationRow()
            }

            Section("Categorieën") {
                SwitchSetting(
                    "Automatisch categoriseren",
                    "Na het transcriberen kiest het chatmodel de passende categorie(ën). Handmatige keuzes worden nooit overschreven.",
                    s.autoCategorize,
                ) { v -> update { it.copy(autoCategorize = v) } }
            }

            Section("Opslag") {
                Text("Audio verwijderen na (tekst blijft bewaard)", style = MaterialTheme.typography.labelLarge)
                val options = listOf(0 to "Nooit", 1 to "1 dag", 7 to "7 dagen", 30 to "30 dagen", 90 to "90 dagen")
                options.forEach { (days, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = s.audioRetentionDays == days, role = Role.RadioButton) { update { it.copy(audioRetentionDays = days) } }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.audioRetentionDays == days, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }

            Section("Kosten") {
                Text(
                    "Tarief per audiominuut in USD, gebruikt voor de kostenschatting. Leeg = standaardtarief van OpenAI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TranscriptionModels.all.forEach { m ->
                    val current = s.rateOverrides[m.id]?.toString() ?: ""
                    TextSetting("${m.label} (standaard ${m.defaultRateUsdPerMinute})", current, keyboard = KeyboardType.Decimal) { v ->
                        val parsed = v.replace(',', '.').toDoubleOrNull()
                        update { st -> st.copy(rateOverrides = if (parsed == null) st.rateOverrides - m.id else st.rateOverrides + (m.id to parsed)) }
                    }
                }
            }

            Section("Android Auto") {
                SwitchSetting("Gesproken melding", "Zegt \"Transcriptie klaar\" in de auto wanneer een transcriptie af is.", s.carTts) { v ->
                    update { it.copy(carTts = v) }
                }
            }

            Text(
                "AKO Notulist ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · geen analytics of trackers; audio gaat alleen naar de OpenAI API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ApiKeySection() {
    val c = LocalContext.current.appContainer
    var hasKey by remember { mutableStateOf(c.secrets.hasApiKey()) }
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Section("OpenAI API-sleutel") {
        Text(
            if (hasKey) "Er is een sleutel opgeslagen (versleuteld met de Android Keystore)." else "Nog geen sleutel ingesteld.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(if (hasKey) "Nieuwe sleutel (sk-…)" else "Sleutel (sk-…)") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "Verbergen" else "Tonen")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = input.isNotBlank(), onClick = {
                c.secrets.setApiKey(input)
                input = ""
                hasKey = true
                status = "Sleutel opgeslagen."
            }) { Text("Opslaan") }
            if (hasKey) {
                OutlinedButton(onClick = {
                    status = "Sleutel testen…"
                    scope.launch { status = c.testApiKey() }
                }) { Text("Test") }
                OutlinedButton(onClick = {
                    c.secrets.setApiKey(null)
                    hasKey = false
                    status = "Sleutel verwijderd."
                }) { Text("Wissen") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = checked, role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Text field that commits on every change but keeps its own editing state (no cursor jumps). */
@Composable
private fun TextSetting(
    label: String,
    value: String,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    onCommit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Battery optimisation status. Apps on Android's battery allow-list may run the transcription as
 * a foreground task from the background, so long recordings finish in one go instead of in rounds.
 */
@android.annotation.SuppressLint("BatteryLife")
@Composable
private fun BatteryOptimizationRow() {
    val ctx = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var restricted by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            restricted = !ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        }
    }
    Text(
        if (restricted) "Batterijbeperking staat AAN. Android kan het transcriberen van lange opnames op de achtergrond onderbreken; " +
            "de app werkt dan in korte rondes en is daardoor trager."
        else "Batterijbeperking staat uit: lange opnames worden in één keer op de achtergrond getranscribeerd. 👍",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (restricted) {
        Button(onClick = {
            val i = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${ctx.packageName}".toUri(),
            )
            runCatching { ctx.startActivity(i) }.onFailure {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }) { Text("Beperking opheffen") }
        Text(
            "Samsung: controleer ook Instellingen → Batterij → Limieten achtergrondgebruik, en zet AKO Notulist bij \"Nooit in slaapstand\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
