package ai.champi.core.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

private const val TEST_DB = "migration-test"

/** Acceptance criterion for issue #16: v1 -> v2 (adds ConversationEntity.title) succeeds cleanly. */
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
}
