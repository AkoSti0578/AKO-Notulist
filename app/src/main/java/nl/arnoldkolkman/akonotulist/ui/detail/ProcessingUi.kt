package nl.arnoldkolkman.akonotulist.ui.detail

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.core.net.toUri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import nl.arnoldkolkman.akonotulist.appContainer

/**
 * Shown while a recording is being processed when Android may throttle the app in the
 * background (battery optimisation on — very common on Samsung).
 */
@SuppressLint("BatteryLife") // sideloaded personal app; long uploads must not be frozen
@Composable
fun BackgroundWorkHint(onShowLog: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var restricted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            restricted = !pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (restricted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BatteryAlert, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Android beperkt AKO Notulist op de achtergrond. Lange opnames gaan sneller en betrouwbaarder zonder die beperking.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row {
                    TextButton(onClick = {
                        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri())
                        runCatching { ctx.startActivity(i) }.onFailure {
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text("Beperking opheffen") }
                    TextButton(onClick = onShowLog) { Text("Verwerkingslog") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tip: houd de app open (☀ scherm aanhouden) voor de snelste verwerking.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onShowLog) { Text("Log") }
                }
            }
        }
    }
}

/** Live view of the per-recording processing log, with copy button. */
@Composable
fun ProcessingLogDialog(recordingId: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val log = ctx.appContainer.processingLog
    var text by remember { mutableStateOf(log.read(recordingId)) }
    LaunchedEffect(recordingId) {
        while (true) {
            text = log.read(recordingId)
            delay(2_000)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verwerkingslog") },
        text = {
            val scroll = rememberScrollState()
            LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
            SelectionContainer {
                Text(
                    text.ifBlank { "Nog geen meldingen voor deze opname." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(scroll).horizontalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Sluiten") } },
        dismissButton = {
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Verwerkingslog", text))
                Toast.makeText(ctx, "Log gekopieerd", Toast.LENGTH_SHORT).show()
            }) { Text("Kopiëren") }
        },
    )
}
