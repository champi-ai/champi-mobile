package ai.champi.core.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, QueuedTurnEntity::class, RoutingDecisionEntity::class],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun queuedTurnDao(): QueuedTurnDao
    abstract fun routingDecisionDao(): RoutingDecisionDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN title TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queued_turns (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                conversationId TEXT NOT NULL,
                inputText TEXT NOT NULL,
                inputAudioPath TEXT,
                enqueuedAt INTEGER NOT NULL,
                retryCount INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS routing_decisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                stage TEXT NOT NULL,
                selectedProviderId TEXT NOT NULL,
                locality TEXT NOT NULL,
                reason TEXT NOT NULL,
                inputTokenEstimate INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentUri TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentType TEXT")
    }
}
