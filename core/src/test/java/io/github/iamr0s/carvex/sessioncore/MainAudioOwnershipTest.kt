package io.github.iamr0s.carvex.sessioncore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The ownership decision is what keeps a second main stream from being opened while the receiver
 *  owns main audio, so each transition is pinned here rather than only in the transport tests. */
class MainAudioOwnershipTest {
    @Test fun transitionBeginIsVisibleBeforeAnnouncementAndFailureIsPreserved()=runBlocking {
        val logs=mutableListOf<String>()
        val failure=java.io.IOException("test")
        val owner=MainAudioOwnership({logs+=it}){
            assertTrue(logs.last().startsWith("MainAudio transition phase=begin phone=true"))
            throw failure
        }
        try{owner.takeOwn();fail("must propagate")}catch(t:java.io.IOException){assertSame(failure,t)}
        assertEquals(MainAudioOwnership.State.UNKNOWN,owner.state())
        assertTrue(logs.last().contains("phase=failed phone=true"))
        assertTrue(logs.last().contains("error=IOException"))
    }
    @Test fun successfulTransitionsHaveBeginEndPairs()=runBlocking {
        val logs=mutableListOf<String>()
        val owner=MainAudioOwnership({logs+=it}){true}
        owner.takeOwn();owner.releaseOwn()
        val transitions=logs.filter{it.startsWith("MainAudio transition")}
        assertEquals(4,transitions.size)
        assertTrue(transitions[0].contains("phase=begin phone=true"))
        assertTrue(transitions[1].contains("phase=end phone=true accepted=true"))
        assertTrue(transitions[2].contains("phase=begin phone=false"))
        assertTrue(transitions[3].contains("phase=end phone=false accepted=true"))
    }
    private class Announce(var accepted:Boolean=true) {
        val calls=AtomicInteger()
        val arguments=mutableListOf<Boolean>()
        suspend fun invoke(phone:Boolean):Boolean{calls.incrementAndGet();arguments.add(phone);return accepted}
    }
    private fun ownerWith(announce:Announce=Announce())=MainAudioOwnership({},announce::invoke)

    @Test fun unknownWithholdsMainOutputUntilOwnershipIsSettled()=runBlocking {
        val announce=Announce()
        val owner=ownerWith(announce)
        assertEquals(MainAudioOwnership.State.UNKNOWN,owner.state())
        assertFalse("unknown must not send main audio",owner.allowsMainSend())
        owner.takeOwn()
        assertEquals(MainAudioOwnership.State.OURS,owner.state())
        assertTrue(owner.allowsMainSend())
        assertEquals(listOf(true),announce.arguments)
    }
    @Test fun receiverHeldMainSuppressesSetupAndNeverAnnounces()=runBlocking {
        val announce=Announce()
        val owner=ownerWith(announce)
        owner.receiverDeclared(true)
        assertEquals(MainAudioOwnership.State.THEIRS,owner.state())
        assertFalse(owner.allowsMainSend())
        val refused=try{owner.takeOwn();null}catch(t:IllegalStateException){t}
        assertNotNull("receiver-held main audio must suppress a new main SETUP",refused)
        assertEquals("a suppressed SETUP must not touch the control session",0,announce.calls.get())
        assertEquals(MainAudioOwnership.State.THEIRS,owner.state())
    }
    @Test fun receiverWithoutMainAudioStreamIsOursWithoutAnnouncement(){
        val announce=Announce()
        val owner=ownerWith(announce)
        owner.receiverAdvertised(false)
        assertEquals(MainAudioOwnership.State.OURS,owner.state())
        assertTrue(owner.allowsMainSend())
        assertEquals(0,announce.calls.get())
    }
    @Test fun advertisedMainStreamKeepsUnknownUntilWeAnnounce()=runBlocking {
        val announce=Announce()
        val owner=ownerWith(announce)
        owner.receiverAdvertised(true)
        assertEquals("an advertised main stream is not an ownership decision",MainAudioOwnership.State.UNKNOWN,owner.state())
        assertFalse(owner.allowsMainSend())
        owner.takeOwn()
        assertTrue(owner.allowsMainSend())
        assertEquals(1,announce.calls.get())
    }
    @Test fun receiverReleasingOwnershipRestoresMainOutputWithoutAnnouncing()=runBlocking {
        val announce=Announce()
        val owner=ownerWith(announce)
        owner.receiverDeclared(true)
        assertFalse(owner.allowsMainSend())
        owner.receiverDeclared(false)
        assertEquals(MainAudioOwnership.State.OURS,owner.state())
        assertTrue(owner.allowsMainSend())
        assertEquals("the receiver's own declaration needs no announcement from us",0,announce.calls.get())
        owner.takeOwn()
        assertEquals("already accepted: takeOwn must not announce twice",0,announce.calls.get())
    }
    @Test fun receiverTakingOverClearsOurAnnouncedHold()=runBlocking {
        val announce=Announce()
        val owner=ownerWith(announce)
        owner.takeOwn()
        assertTrue(owner.ownHoldAccepted())
        owner.receiverDeclared(true)
        assertEquals(MainAudioOwnership.State.THEIRS,owner.state())
        assertFalse("a hold the receiver took over is gone",owner.ownHoldAccepted())
        assertFalse(owner.allowsMainSend())
    }
    @Test fun rejectedAnnouncementLeavesUnknownAndCanBeRetried()=runBlocking {
        val announce=Announce(accepted=false)
        val owner=ownerWith(announce)
        val refused=try{owner.takeOwn();null}catch(t:IllegalStateException){t}
        assertNotNull("a rejected announcement must fail loudly instead of sending",refused)
        assertEquals(MainAudioOwnership.State.UNKNOWN,owner.state())
        assertFalse(owner.allowsMainSend())
        announce.accepted=true
        owner.takeOwn()
        assertEquals(MainAudioOwnership.State.OURS,owner.state())
        assertTrue(owner.allowsMainSend())
        assertEquals(2,announce.calls.get())
    }
    @Test fun releasingOwnHoldReturnsToUnknownAndDoesNotAnnounceTwice()=runBlocking {
        val announce=Announce()
        val owner=ownerWith(announce)
        owner.takeOwn()
        owner.releaseOwn()
        assertEquals(MainAudioOwnership.State.UNKNOWN,owner.state())
        assertFalse(owner.allowsMainSend())
        assertEquals(listOf(true,false),announce.arguments)
        owner.releaseOwn()
        assertEquals("an already released hold must not announce again",2,announce.calls.get())
    }
    @Test fun takeoverThenReleaseRequiresAFreshAnnouncement()=runBlocking {
        val announce=Announce()
        val owner=ownerWith(announce)
        owner.takeOwn()
        owner.receiverDeclared(true)
        owner.receiverDeclared(false)
        assertEquals(MainAudioOwnership.State.OURS,owner.state())
        assertTrue(owner.ownHoldAccepted())
        assertEquals(1,announce.calls.get())
    }
}
