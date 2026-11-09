package nl.arnoldkolkman.akonotulist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.ui.detail.DetailScreen
import nl.arnoldkolkman.akonotulist.ui.list.RecordingsScreen
import nl.arnoldkolkman.akonotulist.ui.search.SearchScreen
import nl.arnoldkolkman.akonotulist.ui.settings.SettingsScreen
import nl.arnoldkolkman.akonotulist.ui.theme.AkoTheme

class MainActivity : ComponentActivity() {

    /** Recording to open (from a notification tap). Consumed by the NavHost. */
    private val pendingOpen = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            AkoTheme {
                val nav = rememberNavController()
                val toOpen = pendingOpen.value
                LaunchedEffect(toOpen) {
                    if (toOpen != null) {
                        nav.navigate("detail/$toOpen") { launchSingleTop = true }
                        pendingOpen.value = null
                    }
                }
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        RecordingsScreen(
                            onOpen = { nav.navigate("detail/$it") },
                            onSearch = { nav.navigate("search") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable(
                        "detail/{id}?seek={seek}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.LongType },
                            navArgument("seek") { type = NavType.LongType; defaultValue = -1L },
                        ),
                    ) { entry ->
                        DetailScreen(
                            recordingId = entry.arguments?.getLong("id") ?: 0,
                            initialSeekMs = entry.arguments?.getLong("seek")?.takeIf { it >= 0 },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("search") {
                        SearchScreen(
                            onBack = { nav.popBackStack() },
                            onOpen = { id, seek -> nav.navigate("detail/$id?seek=$seek") },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getLongExtra(EXTRA_RECORDING_ID, -1).takeIf { it > 0 }?.let { pendingOpen.value = it }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        // Prevent re-import on configuration change / recreation.
        intent.action = Intent.ACTION_MAIN
        lifecycleScope.launch {
            var ok = 0
            uris.forEach { uri -> runCatching { appContainer.importer.import(uri) }.onSuccess { ok++ } }
            val msg = if (ok == uris.size) "$ok bestand(en) geïmporteerd" else "Importeren deels mislukt ($ok van ${uris.size})"
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_RECORDING_ID = "recording_id"
    }
}
