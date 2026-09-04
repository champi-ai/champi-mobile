package ai.champi.assistant

import ai.champi.core.persistence.AppDatabase
import ai.champi.core.persistence.MIGRATION_1_2
import ai.champi.core.persistence.MessageRole
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var manager: ConversationManager

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        manager = ConversationManager(db.messageDao())
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun appendedMessageSurvivesReopeningANewManagerAgainstTheSameDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "conv_test_persist.db"
        context.deleteDatabase(dbName)
        try {
            runBlocking {
                Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .let { ConversationManager(it.messageDao()) }
                    .appendUserMessage("hello from before restart")

                // A fresh ConversationManager (backed by a fresh Room instance, but the same
                // underlying database file) simulates the process restarting: it must find the
                // existing conversation rather than starting a blank one.
                val reopened = ConversationManager(
                    Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                        .addMigrations(MIGRATION_1_2)
                        .build()
                        .messageDao(),
                )
                val messages = withTimeout(2000) { reopened.messages.first { it.isNotEmpty() } }

                assertEquals(1, messages.size)
                assertEquals("hello from before restart", messages.single().content)
                assertEquals(MessageRole.USER, messages.single().role)
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun messagesFlowEmitsWithinOneHundredMillisOfAppending() = runBlocking {
        // Establish the initial (empty) collection before appending, otherwise a fast append could
        // race manager initialization and this would trivially observe the post-append state.
        val initial = manager.messages.first()
        assertTrue(initial.isEmpty())

        val start = System.currentTimeMillis()
        manager.appendUserMessage("ping")
        val updated = withTimeout(100) { manager.messages.first { it.isNotEmpty() } }

        assertTrue(System.currentTimeMillis() - start < 100)
        assertEquals("ping", updated.single().content)
    }

    @Test
    fun clearConversationEmptiesTheMessageList() = runBlocking {
        manager.appendUserMessage("will be cleared")
        withTimeout(2000) { manager.messages.first { it.isNotEmpty() } }

        manager.clearConversation()

        val afterClear = withTimeout(2000) { manager.messages.first() }
        assertTrue(afterClear.isEmpty())
    }

    @Test
    fun appendUserThenAssistantPreservesOrderAndRoles() = runBlocking {
        manager.appendUserMessage("question")
        manager.appendAssistantMessage("answer", providerMetadata = """{"provider":"edge"}""")

        val messages = withTimeout(2000) { manager.messages.first { it.size == 2 } }

        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals("""{"provider":"edge"}""", messages[1].providerMetadata)
    }
}
