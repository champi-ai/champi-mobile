package ai.champi.core.persistence

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance criteria for issues #32 and #35: QueuedTurnEntity round-trips through insert/query/delete/update. */
@RunWith(AndroidJUnit4::class)
class QueuedTurnDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: QueuedTurnDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.queuedTurnDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertedTurnCanBeRetrievedAsOldest() = runBlocking {
        val turn = QueuedTurnEntity(
            conversationId = "c1",
            inputText = "hello",
            enqueuedAt = 1_000L,
        )
        dao.insert(turn)

        val oldest = dao.getOldest()

        assertNotNull(oldest)
        assertEquals("c1", oldest!!.conversationId)
        assertEquals("hello", oldest.inputText)
        assertNull(oldest.inputAudioPath)
        assertEquals(0, oldest.retryCount)
    }

    @Test
    fun getOldestReturnsEarliestByEnqueuedAt() = runBlocking {
        dao.insert(QueuedTurnEntity(conversationId = "c1", inputText = "second", enqueuedAt = 2_000L))
        dao.insert(QueuedTurnEntity(conversationId = "c1", inputText = "first", enqueuedAt = 1_000L))

        val oldest = dao.getOldest()

        assertEquals("first", oldest!!.inputText)
    }

    @Test
    fun deletedTurnNoLongerAppearsAsOldest() = runBlocking {
        val turn = QueuedTurnEntity(
            conversationId = "c1",
            inputText = "to delete",
            enqueuedAt = 1_000L,
        )
        val id = dao.insert(turn)
        val inserted = dao.getOldest()!!

        dao.delete(inserted)

        assertNull(dao.getOldest())
    }

    @Test
    fun queueIsEmptyInitially() = runBlocking {
        assertNull(dao.getOldest())
    }

    @Test
    fun turnWithAudioPathRoundTrips() = runBlocking {
        dao.insert(
            QueuedTurnEntity(
                conversationId = "c2",
                inputText = "voice turn",
                inputAudioPath = "/data/user/0/ai.champi/cache/turn_1.pcm",
                enqueuedAt = 5_000L,
                retryCount = 2,
            ),
        )

        val oldest = dao.getOldest()!!

        assertEquals("/data/user/0/ai.champi/cache/turn_1.pcm", oldest.inputAudioPath)
        assertEquals(2, oldest.retryCount)
    }

    @Test
    fun messageCountAtEnqueueRoundTrips() = runBlocking {
        dao.insert(
            QueuedTurnEntity(
                conversationId = "c1",
                inputText = "with count",
                enqueuedAt = 1_000L,
                messageCountAtEnqueue = 7,
            ),
        )

        val oldest = dao.getOldest()!!
        assertEquals(7, oldest.messageCountAtEnqueue)
    }

    @Test
    fun updateIncrementsRetryCount() = runBlocking {
        dao.insert(QueuedTurnEntity(conversationId = "c1", inputText = "retry me", enqueuedAt = 1_000L))

        val inserted = dao.getOldest()!!
        dao.update(inserted.copy(retryCount = inserted.retryCount + 1))

        val updated = dao.getOldest()!!
        assertEquals(1, updated.retryCount)
    }
}
