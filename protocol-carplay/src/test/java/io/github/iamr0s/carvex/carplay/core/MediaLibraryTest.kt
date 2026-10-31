package io.github.iamr0s.carvex.carplay.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryTest {
    private fun group(vararg children: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((id, payload) in children) {
            val total = 4 + payload.size
            out.write((total ushr 8) and 0xff); out.write(total and 0xff)
            out.write((id ushr 8) and 0xff); out.write(id and 0xff)
            out.write(payload)
        }
        return out.toByteArray()
    }

    private fun text(value: String) = value.toByteArray() + byteArrayOf(0)

    @Test fun subscriptionWithoutLibraryUidIsRejected(){
        assertNull(MediaLibrary.startUpdates(IapFields(listOf(1 to text("rev")))))
    }

    @Test fun subscriptionRecordsMasksAndIgnoresUnknownChildren(){
        MediaLibrary.advertise("lib-1")
        val fields = IapFields(listOf(
            0 to text("lib-1"),
            1 to text("42"),
            2 to group(0 to ByteArray(0), 0x13 to ByteArray(0), 0x19 to ByteArray(0)),
            3 to group(0 to ByteArray(0), 5 to ByteArray(0), 7 to ByteArray(0)),
            7 to group(1 to ByteArray(0), 0x0c to ByteArray(0)),
        ))
        val parsed = MediaLibrary.startUpdates(fields)!!
        assertEquals("lib-1", parsed.libraryUid)
        assertEquals("42", parsed.lastRevision)
        assertEquals(setOf(0, 0x13), parsed.mediaItemProperties)
        assertEquals(setOf(0), parsed.playlistProperties)
        assertEquals(3, parsed.playlistOptions) // 5 → 1、7 → 2
        assertEquals(setOf(1, 0x0c), parsed.playlistContentProperties)
        assertTrue(MediaLibrary.updatesActive)
    }

    @Test fun nonEmptyGroupChildRejectsTheMessage(){
        val fields = IapFields(listOf(
            0 to text("lib"),
            2 to group(0 to byteArrayOf(1)),
        ))
        assertNull(MediaLibrary.startUpdates(fields))
    }

    @Test fun playRequestsValidatePerFirmware(){
        val uid = text("lib")
        assertTrue(MediaLibrary.playCurrentSelection(IapFields(listOf(0 to uid))))
        assertFalse(MediaLibrary.playCurrentSelection(IapFields(listOf(0 to ByteArray(0)))))
        assertFalse(MediaLibrary.playItems(IapFields(listOf(0 to ByteArray(4), 2 to uid))))
        assertTrue(MediaLibrary.playItems(IapFields(listOf(0 to ByteArray(8) { 1 }, 2 to uid))))
        assertFalse(MediaLibrary.playCollection(IapFields(listOf(0 to ByteArray(8), 1 to byteArrayOf(0), 3 to uid))))
        assertTrue(MediaLibrary.playCollection(IapFields(listOf(0 to ByteArray(8) { 1 }, 1 to byteArrayOf(0), 3 to uid))))
        assertFalse(MediaLibrary.playCollection(IapFields(listOf(0 to ByteArray(8) { 1 }, 1 to byteArrayOf(8), 3 to uid))))
        assertTrue(MediaLibrary.playAllSongs(IapFields(listOf(0 to uid, 1 to ByteArray(0)))))
        assertFalse(MediaLibrary.playAllSongs(IapFields(listOf(0 to uid))))
    }

    @Test fun stopClearsTheSubscription(){
        MediaLibrary.startInfo()
        assertTrue(MediaLibrary.infoActive)
        MediaLibrary.stopInfo()
        assertFalse(MediaLibrary.infoActive)
        assertFalse(MediaLibrary.stopUpdates(IapFields(listOf(0 to text("unknown")))))
        assertFalse(MediaLibrary.updatesActive)
        assertNull(MediaLibrary.subscription)
    }
}
