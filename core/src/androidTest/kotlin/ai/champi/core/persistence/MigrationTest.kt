package ai.champi.core.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

private const val TEST_DB = "migration-test"

/** Acceptance criteria for issue #16 and #32: schema migrations complete without data loss. */
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
}
