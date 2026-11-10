package nl.arnoldkolkman.akonotulist.category

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.arnoldkolkman.akonotulist.AppContainer
import nl.arnoldkolkman.akonotulist.data.ActionItemStatus
import nl.arnoldkolkman.akonotulist.data.CategoryEntity
import nl.arnoldkolkman.akonotulist.export.ExportActions
import nl.arnoldkolkman.akonotulist.export.ExportFormat
import nl.arnoldkolkman.akonotulist.transcribe.ChatModels
import java.io.File

/** Everything the UI and workers do with categories. */
class CategoryRepository(private val c: AppContainer) {
    private val dao get() = c.db.categories()

    sealed interface SaveResult {
        data class Saved(val id: Long) : SaveResult
        data object NameTaken : SaveResult
        data object EmptyName : SaveResult
    }

    suspend fun save(category: CategoryEntity): SaveResult {
        val name = category.name.trim()
        if (name.isEmpty()) return SaveResult.EmptyName
        return try {
            if (category.id == 0L) {
                SaveResult.Saved(dao.insert(category.copy(name = name, sortOrder = dao.maxSortOrder() + 1)))
            } else {
                dao.upsert(category.copy(name = name))
                SaveResult.Saved(category.id)
            }
        } catch (e: SQLiteConstraintException) {
            SaveResult.NameTaken
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun move(id: Long, delta: Int) {
        CategoryInsights.move(dao.all(), id, delta).forEach { dao.upsert(it) }
    }

    suspend fun setForRecording(recordingId: Long, ids: Set<Long>) = dao.setFor(recordingId, ids)

    /** Adds (or removes) one category for many recordings at once (multi-select in the list). */
    suspend fun applyToMany(recordingIds: Collection<Long>, categoryId: Long, add: Boolean) {
        recordingIds.forEach { rid ->
            if (add) dao.assign(nl.arnoldkolkman.akonotulist.data.RecordingCategoryRef(rid, categoryId))
            else dao.unassign(rid, categoryId)
        }
    }

    suspend fun setActionDone(recordingId: Long, index: Int, text: String, done: Boolean) =
        c.db.actionItems().upsert(ActionItemStatus(recordingId, index, text, done, if (done) System.currentTimeMillis() else null))

    /**
     * Lets the chat model pick 0–2 of the user's categories. Only runs when the user has
     * categories; with [onlyIfEmpty] it never overrides a manual choice.
     * @return the assigned category ids.
     */
    suspend fun autoCategorize(recordingId: Long, onlyIfEmpty: Boolean = true): List<Long> {
        val categories = dao.all()
        if (categories.isEmpty()) return emptyList()
        if (onlyIfEmpty && dao.refsFor(recordingId).isNotEmpty()) return emptyList()
        val rec = c.db.recordings().get(recordingId) ?: return emptyList()
        val summary = c.db.summaries().get(recordingId)?.summary
        val head = c.db.segments().forRecording(recordingId).joinToString(" ") { it.text }.take(6_000)
        if (summary.isNullOrBlank() && head.isBlank()) return emptyList()
        val model = c.settings.current().summaryModel.ifBlank { ChatModels.DEFAULT }
        val out = c.openAi.chatJson(
            model, CategoryPrompts.system, CategoryPrompts.userMessage(categories, rec.title, summary, head),
            "categorisation", CategoryPrompts.schema,
        )
        val ids = CategoryPrompts.parse(out, categories)
        ids.forEach { dao.assign(nl.arnoldkolkman.akonotulist.data.RecordingCategoryRef(recordingId, it, byAi = true)) }
        return ids
    }

    suspend fun reportInput(categoryId: Long): CategoryReport.Input? {
        val category = dao.observe(categoryId).first() ?: return null
        val recordings = dao.observeRecordings(categoryId).first()
        val summaries = dao.observeSummaries(categoryId).first()
        val statuses = c.db.actionItems().observeAll().first()
        val actions = CategoryInsights.actions(recordings, summaries, statuses)
        val decisions = CategoryInsights.decisions(recordings, summaries)
        return CategoryReport.Input(category, recordings, summaries, actions, decisions, CategoryInsights.stats(recordings, actions, decisions))
    }

    /** Writes the category report and opens the share sheet. */
    suspend fun shareReport(context: Context, categoryId: Long, format: ExportFormat) {
        val input = reportInput(categoryId) ?: return
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, ExportActions.safeFileName("Categorie " + input.category.name) + "." + format.extension)
            when (format) {
                ExportFormat.TXT -> f.writeText(CategoryReport.toText(input))
                ExportFormat.MARKDOWN -> f.writeText(CategoryReport.toMarkdown(input))
                ExportFormat.DOCX -> f.writeBytes(CategoryReport.toDocx(input))
            }
            f
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Categorie: ${input.category.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Categorierapport delen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** Ready-made suggestions for the empty state. */
        val suggestions = listOf(
            CategoryEntity(name = "Werk", emoji = "💼", colorIndex = 0, description = "Zakelijke afspraken en werkoverleg"),
            CategoryEntity(name = "Klanten", emoji = "🤝", colorIndex = 2, description = "Gesprekken met klanten en offertes"),
            CategoryEntity(name = "Overleg", emoji = "📋", colorIndex = 4, description = "Teamvergaderingen en stand-ups"),
            CategoryEntity(name = "Ideeën", emoji = "💡", colorIndex = 5, description = "Losse gedachten, plannen en brainstorms"),
            CategoryEntity(name = "Privé", emoji = "🏠", colorIndex = 6, description = "Persoonlijke notities en gesprekken"),
        )
    }
}
