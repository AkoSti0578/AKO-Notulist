package nl.arnoldkolkman.akonotulist.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of a recording, from capture to finished transcript. */
enum class RecordingStatus {
    RECORDING,
    IMPORTING,
    RECORDED,
    QUEUED,
    TRANSCRIBING,
    SUMMARIZING,
    DONE,
    FAILED;

    val isBusy: Boolean get() = this == QUEUED || this == TRANSCRIBING || this == SUMMARIZING || this == IMPORTING
}

enum class RecordingSource { MICROPHONE, IMPORT }

@Entity(tableName = "recordings", indices = [Index("createdAt")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    /** Absolute path of the audio file; null once the retention policy deleted it. */
    val audioPath: String?,
    val source: RecordingSource,
    val status: RecordingStatus,
    /** 0..100 progress for the current busy status. */
    val progress: Int = 0,
    val statusDetail: String? = null,
    val errorMessage: String? = null,
    val transcriptionModel: String? = null,
    val language: String? = null,
    /** Estimated transcription cost in USD, based on the configured rate. */
    val estimatedCostUsd: Double? = null,
    /** True when segment timestamps are estimated (model returned no timestamps). */
    val timestampsEstimated: Boolean = false,
    val audioDeletedAt: Long? = null,
)

/** One audio chunk sent to the transcription API. Persisted so retries never re-pay finished chunks. */
@Entity(
    tableName = "chunks",
    primaryKeys = ["recordingId", "chunkIndex"],
    foreignKeys = [ForeignKey(RecordingEntity::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
)
data class ChunkEntity(
    val recordingId: Long,
    val chunkIndex: Int,
    /** Planned window in the source audio. */
    val startMs: Long,
    val endMs: Long,
    /** Raw API result (our own normalized JSON), null until transcribed. */
    val resultJson: String? = null,
    val model: String? = null,
)

@Entity(
    tableName = "segments",
    indices = [Index("recordingId")],
    foreignKeys = [ForeignKey(RecordingEntity::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val position: Int,
    val startMs: Long,
    val endMs: Long,
    /** Stable speaker key, e.g. "A". Display names live in [SpeakerEntity]. Null = unknown. */
    val speaker: String?,
    val text: String,
)

@Fts4(contentEntity = SegmentEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "segments_fts")
data class SegmentFts(val text: String)

@Entity(
    tableName = "speakers",
    primaryKeys = ["recordingId", "label"],
    foreignKeys = [ForeignKey(RecordingEntity::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
)
data class SpeakerEntity(
    val recordingId: Long,
    val label: String,
    val displayName: String,
)

/** A moment the user flagged during recording ("belangrijk"). */
@Entity(
    tableName = "markers",
    indices = [Index("recordingId")],
    foreignKeys = [ForeignKey(RecordingEntity::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
)
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    /** Position in the recording (pauses excluded), in ms. */
    val timeMs: Long,
    val label: String = "",
)

@Entity(
    tableName = "summaries",
    foreignKeys = [ForeignKey(RecordingEntity::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
)
data class SummaryEntity(
    @PrimaryKey val recordingId: Long,
    val summary: String,
    /** JSON array of strings. */
    val actionItemsJson: String,
    /** JSON array of strings. */
    val decisionsJson: String,
    val model: String,
    val createdAt: Long,
)
