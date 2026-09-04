package ai.champi.context

import ai.champi.core.conversation.AttachmentType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Unit tests for pure functions in [ShareReceiver] and [ShareCacheManager]. */
class ShareAttachmentTypeTest {

    @Test
    fun imageMimeTypesMapToImageAttachmentType() {
        assertEquals(AttachmentType.IMAGE, attachmentTypeForMime("image/jpeg"))
        assertEquals(AttachmentType.IMAGE, attachmentTypeForMime("image/png"))
        assertEquals(AttachmentType.IMAGE, attachmentTypeForMime("image/gif"))
        assertEquals(AttachmentType.IMAGE, attachmentTypeForMime("image/webp"))
        assertEquals(AttachmentType.IMAGE, attachmentTypeForMime("image/*"))
    }

    @Test
    fun nonImageMimeTypesMapToFileAttachmentType() {
        assertEquals(AttachmentType.FILE, attachmentTypeForMime("application/pdf"))
        assertEquals(AttachmentType.FILE, attachmentTypeForMime("text/plain"))
        assertEquals(AttachmentType.FILE, attachmentTypeForMime("text/html"))
        assertEquals(AttachmentType.FILE, attachmentTypeForMime("*/*"))
        assertEquals(AttachmentType.FILE, attachmentTypeForMime("application/octet-stream"))
    }

    @Test
    fun extensionForMimeReturnsCorrectExtensions() {
        assertEquals(".jpg", extensionForMime("image/jpeg"))
        assertEquals(".png", extensionForMime("image/png"))
        assertEquals(".gif", extensionForMime("image/gif"))
        assertEquals(".webp", extensionForMime("image/webp"))
        assertEquals(".pdf", extensionForMime("application/pdf"))
        assertEquals(".txt", extensionForMime("text/plain"))
        assertEquals(".html", extensionForMime("text/html"))
        assertEquals("", extensionForMime("application/octet-stream"))
    }

    @Test
    fun evictIfNeededDeletesOldestFilesWhenOverLimit() {
        val dir = kotlin.io.path.createTempDirectory("share_cache_test").toFile()
        try {
            // Write 3 files of 20 MB each = 60 MB total (over 50 MB limit).
            val twentyMb = ByteArray(20 * 1024 * 1024)
            val fileA = File(dir, "a.bin").also { it.writeBytes(twentyMb) }
            Thread.sleep(10)
            val fileB = File(dir, "b.bin").also { it.writeBytes(twentyMb) }
            Thread.sleep(10)
            val fileC = File(dir, "c.bin").also { it.writeBytes(twentyMb) }

            // Stamp last-modified times explicitly to guarantee ordering across file systems.
            fileA.setLastModified(1000L)
            fileB.setLastModified(2000L)
            fileC.setLastModified(3000L)

            evictIfNeeded(dir)

            // Oldest file deleted, two newest survive (40 MB ≤ 50 MB).
            assertEquals(false, fileA.exists())
            assertEquals(true, fileB.exists())
            assertEquals(true, fileC.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun evictIfNeededDoesNothingWhenUnderLimit() {
        val dir = kotlin.io.path.createTempDirectory("share_cache_test_small").toFile()
        try {
            val smallData = ByteArray(1024)
            val fileA = File(dir, "a.bin").also { it.writeBytes(smallData) }
            val fileB = File(dir, "b.bin").also { it.writeBytes(smallData) }

            evictIfNeeded(dir)

            assertEquals(true, fileA.exists())
            assertEquals(true, fileB.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
