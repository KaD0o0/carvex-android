package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test

class MainAudioResourceModesTest {
    private fun request(resource:Int,transfer:Int)=mapOf<String,Any?>("resources" to listOf(mapOf("resourceID" to resource,"transferType" to transfer)),"appStates" to emptyList<Any>())
    @Test fun mainAudioTakeAndReturnDoNotChangeScreen(){
        val modes=ScreenResourceModes();val before=modes.state
        val taken=modes.propose(request(2,1))!!;assertEquals(2,taken.audioOwner);assertEquals(before.owner,taken.owner)
        modes.commit(taken);assertEquals(2,modes.state.audioOwner)
        val returned=modes.propose(request(2,2))!!;modes.commit(returned);assertEquals(1,modes.state.audioOwner);assertEquals(1,modes.state.owner)
    }
    @Test fun audioBorrowAndUnborrowPreservePermanentEntity(){
        val modes=ScreenResourceModes();modes.audio(true);val borrowed=modes.propose(request(2,3))!!
        assertTrue(borrowed.audioBorrowed);assertEquals(2,borrowed.audioOwner);assertEquals(1,borrowed.audioPermanent)
        modes.commit(borrowed);val returned=modes.propose(request(2,4))!!
        assertFalse(returned.audioBorrowed);assertEquals(1,returned.audioOwner);assertEquals(1,returned.audioPermanent)
    }
    @Test fun unknownResourceAndMalformedAppStatesAreRejected(){
        val modes=ScreenResourceModes();assertNull(modes.propose(request(3,1)))
        assertNull(modes.propose(mapOf("resources" to listOf(mapOf("resourceID" to 2,"transferType" to 1)),"appStates" to listOf(mapOf("x" to 1)))))
    }
    @Test fun permanentTakeAfterPhoneOwnershipReturnsToAccessory(){
        val modes=ScreenResourceModes();modes.audio(true)
        modes.commit(requireNotNull(modes.propose(request(2,1))))
        assertEquals(2,modes.state.audioOwner);assertEquals(2,modes.state.audioPermanent)
        assertFalse(modes.state.audioBorrowed)
        modes.commit(requireNotNull(modes.propose(request(2,2))))
        assertEquals(1,modes.state.audioOwner);assertEquals(1,modes.state.audioPermanent)
    }
    @Test fun binaryPlistResourceIdsSelectTheSameTransitionAsDirectMaps(){
        val modes=ScreenResourceModes();modes.audio(true)
        val decoded=Plist.dictionary(Plist.encode(request(2,3)))
        val entry=(decoded["resources"] as List<*>).single() as Map<*,*>
        assertTrue(entry["resourceID"] is Long)
        assertEquals(2,modes.requestedResource(decoded))
        val borrowed=requireNotNull(modes.propose(decoded));assertEquals(2,borrowed.audioOwner)
        modes.commit(borrowed)
        val returned=Plist.dictionary(Plist.encode(request(2,4)))
        assertEquals(2,modes.requestedResource(returned))
        assertEquals(1,requireNotNull(modes.propose(returned)).audioOwner)
        val screen=Plist.dictionary(Plist.encode(request(1,1)))
        assertEquals(1,modes.requestedResource(screen))
        assertNull(modes.requestedResource(mapOf("resources" to listOf(mapOf("resourceID" to 1.5)))))
    }
}
