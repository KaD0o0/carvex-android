package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class NowPlayingInitialStateTest {
    private val subscription=IapFields(listOf(0 to IapWire.fields(
        listOf(0,1,4,6,12).map { it to byteArrayOf() })))

    // Bounded control-flow model, NOT execution/emulation of Toyota firmware.
    // nowPlayingUpdate calls event3 before setting +0x770; the title getter
    // synchronously waits for +0x770. Detect the cycle instead of hanging a test.
    private class ToyotaInitialState {
        var initialized=false
        var blocked=false
        private var title=""
        fun accept(fields:List<Pair<Int,ByteArray>>):Boolean {
            if(blocked)return false
            val media=fields.firstOrNull { it.first==0 }?.second
                ?.let { IapWire.parseFieldList(it) }
            val next=media?.get(1)?.let { IapWire.text(it) }?:title
            if(next!=title&&!initialized){blocked=true;return true} // ACK precedes callback.
            title=next
            initialized=true
            return true
        }
    }

    @Test fun directFirstRealTitleDemonstratesTheFirmwareWaitCycle(){
        val peer=ToyotaInitialState()
        assertTrue(peer.accept(NowPlayingUpdate.fields(NowPlayingUpdate.Track(title="Song"))))
        assertTrue(peer.blocked)
        assertFalse(peer.accept(NowPlayingUpdate.fields(NowPlayingUpdate.Track(title="Next"))))
    }

    @Test fun subscriptionInitializesBeforeTheFirstRealTitle(){
        val peer=ToyotaInitialState()
        val publisher=NowPlayingPublisher(peer::accept)
        publisher.linkReady(true)
        assertTrue(publisher.command(0x5000,subscription))
        assertTrue(peer.initialized)
        assertTrue(publisher.update(NowPlayingUpdate.Track(title="Song")))
        assertFalse(peer.blocked)
    }

    @Test fun cachedTitleMustFollowDefaultsAndSurviveResubscription(){
        val sent=mutableListOf<List<Pair<Int,ByteArray>>>()
        val publisher=NowPlayingPublisher { sent.add(it);true }
        publisher.update(NowPlayingUpdate.Track(title="Song"))
        publisher.linkReady(true)
        repeat(2){
            sent.clear()
            assertTrue(publisher.command(0x5000,subscription))
            assertEquals(2,sent.size)
            assertEquals("",IapWire.text(IapWire.parseFieldList(sent[0].single().second)[1]!!))
            assertEquals("Song",IapWire.text(IapWire.parseFieldList(sent[1].single().second)[1]!!))
        }
    }

    @Test fun rejectedInitialStateDoesNotAllowRealUpdatesToOvertakeIt(){
        var accept=false
        val sent=mutableListOf<List<Pair<Int,ByteArray>>>()
        val publisher=NowPlayingPublisher { sent.add(it);accept }
        publisher.linkReady(true)
        assertFalse(publisher.command(0x5000,subscription))
        accept=true
        assertFalse(publisher.update(NowPlayingUpdate.Track(title="Song")))
        assertEquals(1,sent.size)
        assertTrue(publisher.command(0x5000,subscription))
        assertEquals(3,sent.size)
    }

    @Test fun initialDefaultsHaveOnlySubscribedFieldsAndPreserveOrder(){
        val sent=mutableListOf<List<Pair<Int,ByteArray>>>()
        val publisher=NowPlayingPublisher { sent.add(it);true }
        publisher.linkReady(true)
        val selected=IapFields(listOf(0 to IapWire.fields(
            listOf(12,1,12,4,0,0x15,0x7fff).map{it to byteArrayOf()})))
        assertTrue(publisher.command(0x5000,selected))
        val media=IapWire.parseFieldList(sent.single().single().second).ordered
        assertEquals(listOf(12,1,12,4,0,0x15),media.map{it.first})
        assertEquals(listOf(1,1,1,4,8,1),media.map{it.second.size})
        assertTrue(media.all{it.second.all{byte->byte==0.toByte()}})
    }

    @Test fun playbackOnlyHasItsOwnDefaultsWithoutInventingMedia(){
        val sent=mutableListOf<List<Pair<Int,ByteArray>>>()
        val publisher=NowPlayingPublisher { sent.add(it);true }
        publisher.linkReady(true)
        assertTrue(publisher.command(0x5000,IapFields(listOf(1 to IapWire.fields(
            listOf(0,1,7,0xc,0xd).map{it to byteArrayOf()})))))
        assertEquals(1,sent.single().single().first)
        val values=IapWire.parseFieldList(sent.single().single().second).ordered
        assertEquals(listOf(1,4,1,2,1),values.map{it.second.size})
        assertTrue(values.all{it.second.all{byte->byte==0.toByte()}})
    }

    @Test fun unsupportedSubscriptionEmitsNothingAndStopDisablesUpdates(){
        val sent=mutableListOf<List<Pair<Int,ByteArray>>>()
        val publisher=NowPlayingPublisher { sent.add(it);true }
        publisher.linkReady(true)
        assertTrue(publisher.command(0x5000,IapFields(listOf(0 to IapWire.fields(
            listOf(0x7fff to byteArrayOf()))))))
        assertTrue(sent.isEmpty())
        publisher.command(0x5000,subscription)
        publisher.command(0x5002,IapFields(emptyList()))
        assertFalse(publisher.update(NowPlayingUpdate.Track(title="Song")))
        assertEquals(1,sent.size)
    }
}
