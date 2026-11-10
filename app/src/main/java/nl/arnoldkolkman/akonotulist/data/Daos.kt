package nl.arnoldkolkman.akonotulist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class SearchHit(
    val recordingId: Long,
    val title: String,
    val createdAt: Long,
    val segmentId: Long,
    val startMs: Long,
    val speaker: String?,
    val text: String,
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE status = :status")
    suspend fun withStatus(status: RecordingStatus): List<RecordingEntity>

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Upsert
    suspend fun upsert(recording: RecordingEntity)

    @Query("UPDATE recordings SET status = :status, progress = :progress, statusDetail = :detail, errorMessage = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus, progress: Int = 0, detail: String? = null, error: String? = null)

    @Query("UPDATE recordings SET progress = :progress, statusDetail = :detail WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Int, detail: String?)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE recordings SET audioPath = NULL, audioDeletedAt = :now WHERE id = :id")
    suspend fun markAudioDeleted(id: Long, now: Long)

    @Query("SELECT * FROM recordings WHERE audioPath IS NOT NULL AND status = 'DONE' AND createdAt < :before")
    suspend fun doneWithAudioBefore(before: Long): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM recordings WHERE title LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 50")
    suspend fun searchTitles(q: String): List<RecordingEntity>
}

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE recordingId = :recordingId ORDER BY chunkIndex")
    suspend fun forRecording(recordingId: Long): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Upsert
    suspend fun upsert(chunk: ChunkEntity)

    @Query("DELETE FROM chunks WHERE recordingId = :recordingId")
    suspend fun deleteFor(recordingId: Long)
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE recordingId = :recordingId ORDER BY position")
    fun observe(recordingId: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE recordingId = :recordingId ORDER BY position")
    suspend fun forRecording(recordingId: Long): List<SegmentEntity>

    @Insert
    suspend fun insertAll(segments: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE recordingId = :recordingId")
    suspend fun deleteFor(recordingId: Long)

    @Transaction
    suspend fun replace(recordingId: Long, segments: List<SegmentEntity>) {
        deleteFor(recordingId)
        insertAll(segments)
    }

    @Query(
        """
        SELECT s.recordingId AS recordingId, r.title AS title, r.createdAt AS createdAt,
               s.id AS segmentId, s.startMs AS startMs, s.speaker AS speaker, s.text AS text
        FROM segments s
        JOIN segments_fts f ON s.id = f.rowid
        JOIN recordings r ON r.id = s.recordingId
        WHERE segments_fts MATCH :match
        ORDER BY r.createdAt DESC, s.position
        LIMIT 300
        """
    )
    suspend fun search(match: String): List<SearchHit>
}

@Dao
interface SpeakerDao {
    @Query("SELECT * FROM speakers WHERE recordingId = :recordingId ORDER BY label")
    fun observe(recordingId: Long): Flow<List<SpeakerEntity>>

    @Query("SELECT * FROM speakers WHERE recordingId = :recordingId ORDER BY label")
    suspend fun forRecording(recordingId: Long): List<SpeakerEntity>

    @Upsert
    suspend fun upsert(speaker: SpeakerEntity)

    @Query("DELETE FROM speakers WHERE recordingId = :recordingId")
    suspend fun deleteFor(recordingId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(speakers: List<SpeakerEntity>)
}

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations WHERE recordingId = :recordingId")
    fun observe(recordingId: Long): Flow<List<TranslationEntity>>

    @Upsert
    suspend fun upsert(t: TranslationEntity)
}

@Dao
interface MarkerDao {
    @Query("SELECT * FROM markers WHERE recordingId = :recordingId ORDER BY timeMs")
    fun observe(recordingId: Long): Flow<List<MarkerEntity>>

    @Query("SELECT * FROM markers WHERE recordingId = :recordingId ORDER BY timeMs")
    suspend fun forRecording(recordingId: Long): List<MarkerEntity>

    @Insert
    suspend fun insert(marker: MarkerEntity): Long

    @Query("DELETE FROM markers WHERE id = :id")
    suspend fun delete(id: Long)
}

data class CategoryWithCount(
    val id: Long,
    val name: String,
    val emoji: String,
    val colorIndex: Int,
    val description: String,
    val sortOrder: Int,
    val createdAt: Long,
    val recordingCount: Int,
) {
    fun toEntity() = CategoryEntity(id, name, emoji, colorIndex, description, sortOrder, createdAt)
}

@Dao
interface CategoryDao {
    @Query(
        """
        SELECT c.*, (SELECT COUNT(*) FROM recording_categories rc WHERE rc.categoryId = c.id) AS recordingCount
        FROM categories c ORDER BY c.sortOrder, c.name COLLATE NOCASE
        """
    )
    fun observeWithCounts(): Flow<List<CategoryWithCount>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    fun observe(id: Long): Flow<CategoryEntity?>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    // --- assignments ---------------------------------------------------------------------

    @Query("SELECT * FROM recording_categories")
    fun observeAllRefs(): Flow<List<RecordingCategoryRef>>

    @Query("SELECT * FROM recording_categories WHERE recordingId = :recordingId")
    suspend fun refsFor(recordingId: Long): List<RecordingCategoryRef>

    @Query("SELECT * FROM recording_categories WHERE recordingId = :recordingId")
    fun observeRefsFor(recordingId: Long): Flow<List<RecordingCategoryRef>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun assign(ref: RecordingCategoryRef)

    @Query("DELETE FROM recording_categories WHERE recordingId = :recordingId AND categoryId = :categoryId")
    suspend fun unassign(recordingId: Long, categoryId: Long)

    @Query("DELETE FROM recording_categories WHERE recordingId = :recordingId")
    suspend fun clearFor(recordingId: Long)

    /** Replaces the categories of [recordingId]; keeps the AI flag of assignments that stay. */
    @Transaction
    suspend fun setFor(recordingId: Long, categoryIds: Set<Long>, byAi: Boolean = false) {
        val existing = refsFor(recordingId).associateBy { it.categoryId }
        clearFor(recordingId)
        categoryIds.forEach { id -> assign(existing[id] ?: RecordingCategoryRef(recordingId, id, byAi)) }
    }

    // --- per-category content ------------------------------------------------------------

    @Query(
        """
        SELECT r.* FROM recordings r JOIN recording_categories rc ON rc.recordingId = r.id
        WHERE rc.categoryId = :categoryId ORDER BY r.createdAt DESC
        """
    )
    fun observeRecordings(categoryId: Long): Flow<List<RecordingEntity>>

    @Query(
        """
        SELECT s.* FROM summaries s JOIN recording_categories rc ON rc.recordingId = s.recordingId
        WHERE rc.categoryId = :categoryId
        """
    )
    fun observeSummaries(categoryId: Long): Flow<List<SummaryEntity>>
}

@Dao
interface ActionItemDao {
    @Query("SELECT * FROM action_item_status")
    fun observeAll(): Flow<List<ActionItemStatus>>

    @Query("SELECT * FROM action_item_status WHERE recordingId = :recordingId")
    fun observe(recordingId: Long): Flow<List<ActionItemStatus>>

    @Upsert
    suspend fun upsert(status: ActionItemStatus)
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM summaries WHERE recordingId = :recordingId")
    fun observe(recordingId: Long): Flow<SummaryEntity?>

    @Query("SELECT * FROM summaries WHERE recordingId = :recordingId")
    suspend fun get(recordingId: Long): SummaryEntity?

    @Upsert
    suspend fun upsert(summary: SummaryEntity)
}
