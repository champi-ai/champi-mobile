package ai.champi.core.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private const val TEST_DB = "migration-test"

/** Acceptance criteria for issues #16, #32, #34, and #46: schema migrations complete without data loss. */
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2AddsTitleColumnWithoutDataLoss() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO conversations (id, createdAt, updatedAt) VALUES ('c1', 0, 0)",
            )
            close()
        }

        // MigrationTestHelper validates the resulting schema against the exported 2.json itself
        // — an IllegalStateException here means the migration doesn't produce the schema Room
        // expects, which is exactly what this test is guarding against.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)
    }

    @Test
    fun migrate2To3AddsQueuedTurnsTable() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO conversations (id, createdAt, updatedAt, title) VALUES ('c1', 0, 0, NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)
        db.execSQL(
            "INSERT INTO queued_turns (conversationId, inputText, inputAudioPath, enqueuedAt, retryCount) VALUES ('c1', 'hello', NULL, 1000, 0)",
        )
        val cursor = db.query("SELECT * FROM queued_turns")
        assert(cursor.count == 1)
        cursor.close()
    }

    @Test
    fun migrate3To4AddsRoutingDecisionsTable() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO conversations (id, createdAt, updatedAt, title) VALUES ('c1', 0, 0, NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        db.execSQL(
            "INSERT INTO routing_decisions (timestamp, stage, selectedProviderId, locality, reason, inputTokenEstimate) VALUES (1000, 'LLM', 'remote-llm', 'REMOTE', 'REMOTE_FALLBACK', 5)",
        )
        val cursor = db.query("SELECT * FROM routing_decisions")
        assertEquals(1, cursor.count)
        cursor.close()
    }

    @Test
    fun migrate4To5AddsAttachmentColumnsToMessages() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO conversations (id, createdAt, updatedAt, title) VALUES ('c1', 0, 0, NULL)",
            )
            execSQL(
                "INSERT INTO messages (id, conversationId, role, content, timestamp) VALUES ('m1', 'c1', 'USER', 'hello', 100)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        // Verify the new columns exist and existing data is intact with NULLs for attachment fields.
        val cursor = db.query("SELECT attachmentUri, attachmentType FROM messages WHERE id = 'm1'")
        assert(cursor.count == 1)
        cursor.moveToFirst()
        val uriIndex = cursor.getColumnIndex("attachmentUri")
        val typeIndex = cursor.getColumnIndex("attachmentType")
        assert(cursor.isNull(uriIndex))
        assert(cursor.isNull(typeIndex))
        cursor.close()

        // Verify a message with attachment fields round-trips correctly.
        db.execSQL(
            "INSERT INTO messages (id, conversationId, role, content, timestamp, attachmentUri, attachmentType) VALUES ('m2', 'c1', 'USER', '[image]', 200, '/cache/img.jpg', 'IMAGE')",
        )
        val cur2 = db.query("SELECT attachmentType FROM messages WHERE id = 'm2'")
        cur2.moveToFirst()
        assert(cur2.getString(0) == "IMAGE")
        cur2.close()
    }
}
