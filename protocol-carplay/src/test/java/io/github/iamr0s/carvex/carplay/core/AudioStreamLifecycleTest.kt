package io.github.iamr0s.carvex.carplay.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AudioStreamLifecycleTest {
    @Test fun replacementWaitsForAcknowledgedTeardown()=runBlocking {
        val gate=AudioStreamLifecycle()
        val old=gate.reserve(101,"default",1)
        assertTrue(gate.isActive(101,"default"))
        assertTrue(gate.beginClose(old))
        assertFalse(gate.isActive(101,"default"))
        val replacement=async(start=CoroutineStart.UNDISPATCHED){gate.reserve(101,"default",2)}
        assertFalse(replacement.isCompleted)
        gate.completeClose(old)
        assertEquals(2L,replacement.await().id)
    }

    @Test fun typeOnlyTeardownAlsoBlocksAnotherPurposeButNotAnotherType()=runBlocking {
        val gate=AudioStreamLifecycle()
        val old=gate.reserve(100,"alert",1)
        gate.beginClose(old)
        val replacement=async(start=CoroutineStart.UNDISPATCHED){gate.reserve(100,"speechRecognition",2)}
        assertFalse(replacement.isCompleted)
        assertEquals(3L,gate.reserve(102,"media",3).id)
        gate.completeClose(old)
        assertEquals(2L,replacement.await().id)
    }

    @Test fun failedTeardownNeverPermitsReplacement()=runBlocking {
        val gate=AudioStreamLifecycle()
        val old=gate.reserve(101,"default",1)
        gate.beginClose(old)
        val waiting=async(start=CoroutineStart.UNDISPATCHED){runCatching{gate.reserve(101,"default",2)}}
        gate.completeClose(old,IOException("EOF"))
        assertTrue(waiting.await().exceptionOrNull() is IOException)
        assertTrue(runCatching{gate.reserve(101,"default",3)}.exceptionOrNull() is IOException)
    }

    @Test fun duplicateCloseCannotRemoveAReplacement()=runBlocking {
        val gate=AudioStreamLifecycle()
        val old=gate.reserve(101,"default",1)
        assertTrue(gate.beginClose(old))
        assertFalse(gate.beginClose(old))
        gate.completeClose(old)
        val replacement=gate.reserve(101,"default",2)
        gate.completeClose(old,IOException("late failure"))
        assertFalse(gate.beginClose(old))
        assertTrue(gate.contains(101,"default"))
        assertTrue(gate.beginClose(replacement))
        gate.completeClose(replacement)
    }

    @Test fun cancelledWaiterDoesNotCancelTheOldClose()=runBlocking {
        val gate=AudioStreamLifecycle()
        val old=gate.reserve(101,"default",1)
        gate.beginClose(old)
        val waiting=async(start=CoroutineStart.UNDISPATCHED){gate.reserve(101,"default",2)}
        waiting.cancelAndJoin()
        gate.completeClose(old)
        assertEquals(3L,gate.reserve(101,"default",3).id)
    }

    @Test fun sessionCloseWakesPendingReplacement()=runBlocking {
        val gate=AudioStreamLifecycle()
        val old=gate.reserve(101,"default",1)
        gate.beginClose(old)
        val waiting=async(start=CoroutineStart.UNDISPATCHED){runCatching{gate.reserve(101,"default",2)}}
        gate.close()
        assertTrue(waiting.await().exceptionOrNull() is IOException)
    }

    @Test fun existingOpenPurposePolicyIsNotSilentlyChanged()=runBlocking {
        val gate=AudioStreamLifecycle()
        val speech=gate.reserve(100,"speechRecognition",1)
        val alert=gate.reserve(100,"alert",2)
        assertTrue(gate.isActive(speech.type,speech.purpose))
        assertTrue(gate.isActive(alert.type,alert.purpose))
        // The gate protects replacements after close begins; it does not assert peer isolation.
        gate.close()
    }
}
