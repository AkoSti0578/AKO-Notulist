package nl.arnoldkolkman.akonotulist.data

import android.content.Context
import androidx.room.AutoMigration
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
        MarkerEntity::class,
        TranslationEntity::class,
        CategoryEntity::class,
        RecordingCategoryRef::class,
        ActionItemStatus::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun chunks(): ChunkDao
    abstract fun segments(): SegmentDao
    abstract fun speakers(): SpeakerDao
    abstract fun summaries(): SummaryDao
    abstract fun markers(): MarkerDao
    abstract fun translations(): TranslationDao
    abstract fun categories(): CategoryDao
    abstract fun actionItems(): ActionItemDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "akonotulist.db").build()
    }
}
