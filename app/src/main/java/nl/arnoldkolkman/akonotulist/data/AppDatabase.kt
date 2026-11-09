package nl.arnoldkolkman.akonotulist.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun statusToString(s: RecordingStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): RecordingStatus = RecordingStatus.valueOf(s)
    @TypeConverter fun sourceToString(s: RecordingSource): String = s.name
    @TypeConverter fun stringToSource(s: String): RecordingSource = RecordingSource.valueOf(s)
}

@Database(
    entities = [
        RecordingEntity::class,
        ChunkEntity::class,
        SegmentEntity::class,
        SegmentFts::class,
        SpeakerEntity::class,
        SummaryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun chunks(): ChunkDao
    abstract fun segments(): SegmentDao
    abstract fun speakers(): SpeakerDao
    abstract fun summaries(): SummaryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "akonotulist.db").build()
    }
}
