package ai.champi.core.persistence

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance criterion for issue #16: insert order round-trips through a query. */
@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.messageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertedMessagesComeBackInInsertionOrder() = runBlocking {
        dao.insertConversation(ConversationEntity(id = "c1", createdAt = 0, updatedAt = 0))
        dao.insertMessage(
            MessageEntity(id = "m1", conversationId = "c1", role = MessageRole.USER, content = "hi", timestamp = 100),
        )
        dao.insertMessage(
            MessageEntity(
                id = "m2",
                conversationId = "c1",
                role = MessageRole.ASSISTANT,
                content = "hello",
                timestamp = 200,
            ),
        )

        val messages = dao.getMessages("c1")

        assertEquals(2, messages.size)
        assertEquals("m1", messages[0].id)
        assertEquals("m2", messages[1].id)
    }

    @Test
    fun deletingConversationCascadesToItsMessages() = runBlocking {
        dao.insertConversation(ConversationEntity(id = "c1", createdAt = 0, updatedAt = 0))
        dao.insertMessage(
            MessageEntity(id = "m1", conversationId = "c1", role = MessageRole.USER, content = "hi", timestamp = 100),
        )

        dao.deleteConversation("c1")

        assertEquals(0, dao.getMessages("c1").size)
    }

    @Test
    fun attachmentFieldsRoundTripThroughDatabase() = runBlocking {
        dao.insertConversation(ConversationEntity(id = "c1", createdAt = 0, updatedAt = 0))
        dao.insertMessage(
            MessageEntity(
                id = "m1",
                conversationId = "c1",
                role = MessageRole.USER,
                content = "[image]",
                timestamp = 100,
                attachmentUri = "/cache/share_attachments/123_photo.jpg",
                attachmentType = "IMAGE",
            ),
        )

        val messages = dao.getMessages("c1")
        assertEquals(1, messages.size)
        assertEquals("/cache/share_attachments/123_photo.jpg", messages[0].attachmentUri)
        assertEquals("IMAGE", messages[0].attachmentType)
    }

    @Test
    fun messageWithoutAttachmentHasNullAttachmentFields() = runBlocking {
        dao.insertConversation(ConversationEntity(id = "c1", createdAt = 0, updatedAt = 0))
        dao.insertMessage(
            MessageEntity(id = "m1", conversationId = "c1", role = MessageRole.USER, content = "hi", timestamp = 100),
        )

        val messages = dao.getMessages("c1")
        assertEquals(1, messages.size)
        assertEquals(null, messages[0].attachmentUri)
        assertEquals(null, messages[0].attachmentType)
    }
}
