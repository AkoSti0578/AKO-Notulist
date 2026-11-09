package nl.arnoldkolkman.akonotulist.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.ui.theme.Coral

/** Dutch, user-facing label for a status. Shared by phone UI and Android Auto. */
fun statusLabel(r: RecordingEntity): String = when (r.status) {
    RecordingStatus.RECORDING -> "Bezig met opnemen"
    RecordingStatus.IMPORTING -> r.statusDetail ?: "Importeren…"
    RecordingStatus.RECORDED -> "Opgenomen, nog niet getranscribeerd"
    RecordingStatus.QUEUED -> r.statusDetail ?: "In de wachtrij (wacht op netwerk)"
    RecordingStatus.TRANSCRIBING -> r.statusDetail ?: "Transcriberen… ${r.progress}%"
    RecordingStatus.SUMMARIZING -> "Samenvatting maken…"
    RecordingStatus.DONE -> "Klaar"
    RecordingStatus.FAILED -> "Mislukt"
}

fun shortStatus(status: RecordingStatus): String = when (status) {
    RecordingStatus.RECORDING -> "Opname"
    RecordingStatus.IMPORTING -> "Importeren"
    RecordingStatus.RECORDED -> "Opgenomen"
    RecordingStatus.QUEUED -> "Wachtrij"
    RecordingStatus.TRANSCRIBING -> "Transcriberen"
    RecordingStatus.SUMMARIZING -> "Samenvatten"
    RecordingStatus.DONE -> "Klaar"
    RecordingStatus.FAILED -> "Mislukt"
}

private fun statusIcon(status: RecordingStatus): ImageVector = when (status) {
    RecordingStatus.RECORDING -> Icons.Filled.FiberManualRecord
    RecordingStatus.IMPORTING -> Icons.Filled.FileDownload
    RecordingStatus.RECORDED -> Icons.Filled.Mic
    RecordingStatus.QUEUED -> Icons.Filled.HourglassTop
    RecordingStatus.TRANSCRIBING -> Icons.Filled.CloudUpload
    RecordingStatus.SUMMARIZING -> Icons.Filled.AutoAwesome
    RecordingStatus.DONE -> Icons.Filled.CheckCircle
    RecordingStatus.FAILED -> Icons.Filled.ErrorOutline
}

@Composable
fun StatusChip(r: RecordingEntity, modifier: Modifier = Modifier) {
    val (bg, fg) = when (r.status) {
        RecordingStatus.DONE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        RecordingStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        RecordingStatus.RECORDING -> Coral.copy(alpha = 0.15f) to Coral
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.status.isBusy) {
                CircularProgressIndicator(
                    progress = { if (r.progress > 0) r.progress / 100f else 0f },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = fg,
                    trackColor = Color.Transparent,
                )
            } else {
                Icon(statusIcon(r.status), contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(6.dp))
            val label = if (r.status == RecordingStatus.TRANSCRIBING && r.progress > 0) "Transcriberen ${r.progress}%" else shortStatus(r.status)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
