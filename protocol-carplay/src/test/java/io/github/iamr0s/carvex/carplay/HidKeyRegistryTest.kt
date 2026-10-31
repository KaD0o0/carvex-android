package io.github.iamr0s.carvex.carplay

import org.junit.Assert.*
import org.junit.Test

class HidKeyRegistryTest {
    private fun hex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    // Two Consumer Control variable bits: next and previous track; six padding bits.
    private val descriptor="050c0901a10109b509b615002501750195028102750695018101c0"
        .chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private fun dispatch(registry:HidKeyRegistry,value:Int,callback:(String,Int,Boolean)->Boolean):Boolean {
        var accepted=true
        registry.devices("keys").forEach{accepted=registry.dispatch(it,it.decode(byteArrayOf(value.toByte())),callback)&&accepted}
        return accepted
    }
    @Test fun keysWorkWithoutConstructingAnyScreenAndTrackCombinedReports(){
        val registry=HidKeyRegistry()
        assertEquals(1,registry.register("keys",descriptor).size)
        val events=mutableListOf<Pair<Int,Boolean>>()
        val callback:(String,Int,Boolean)->Boolean={prefix,usage,down->assertEquals("media",prefix);events+=usage to down;true}
        assertTrue(dispatch(registry,3,callback))
        assertTrue(dispatch(registry,3,callback))
        assertTrue(dispatch(registry,0,callback))
        assertTrue(dispatch(registry,0,callback))
        assertEquals(listOf(0xb5 to true,0xb6 to true,0xb5 to false,0xb6 to false),events)
        assertTrue(registry.devices("other").isEmpty())
    }
    @Test fun partialCallbackFailureRetriesOnlyUndeliveredTransitions(){
        val registry=HidKeyRegistry();registry.register("keys",descriptor)
        val attempts=mutableListOf<Pair<Int,Boolean>>()
        var reject=true
        val callback:(String,Int,Boolean)->Boolean={_,usage,down->attempts+=usage to down;!(usage==0xb6&&reject)}
        assertFalse(dispatch(registry,3,callback))
        reject=false
        assertTrue(dispatch(registry,3,callback))
        reject=true
        assertFalse(dispatch(registry,0,callback))
        reject=false
        assertTrue(dispatch(registry,0,callback))
        assertEquals(listOf(0xb5 to true,0xb6 to true,0xb6 to true,0xb5 to false,0xb6 to false,0xb6 to false),attempts)
    }
    @Test fun exceptionAfterOneDeliveryDoesNotLoseItsPressedState(){
        val registry=HidKeyRegistry();registry.register("keys",descriptor)
        assertThrows(IllegalStateException::class.java){dispatch(registry,3){_,usage,_->if(usage==0xb6)error("host failure");true}}
        val delivered=mutableListOf<Int>()
        assertTrue(dispatch(registry,3){_,usage,_->delivered+=usage;true})
        assertEquals(listOf(0xb6),delivered)
    }

    @Test fun compositeUuidDispatchesTouchAndMediaInsteadOfReturningAfterTouch(){
        val descriptor=hex("050d0904a1018501050d0922a102094215002501750195018102750795018101050109300931150026ff00750895028102c0"+
            "050c09b515002501750195018102750795018101c0")
        val registry=HidKeyRegistry();registry.register("combined",descriptor)
        val touches=mutableListOf<Boolean>();val keys=mutableListOf<Pair<Int,Boolean>>()
        val touch=io.github.iamr0s.carvex.carplay.core.HidTouch.parse(descriptor)
        val result=registry.report("combined",byteArrayOf(1,1,64,-128,1),touch,256,256,
            {touches+=it.pressed;true},{_,usage,down->keys+=usage to down;true})
        assertTrue(result.recognized);assertTrue(result.accepted);assertTrue(result.touchMatched)
        assertEquals(listOf(true),touches)
        assertTrue(keys.contains(0xb5 to true))
    }

    @Test fun differentReportIdUnderTouchUuidReachesKeysAndMalformedReportIsNotAcknowledged(){
        val descriptor=hex("050d0904a1018501050d0922a102094215002501750195018102750795018101050109300931150026ff00750895028102c0c0"+
            "050c0901a101850209b515002501750195018102750795018101c0")
        val registry=HidKeyRegistry();registry.register("combined",descriptor)
        val touch=io.github.iamr0s.carvex.carplay.core.HidTouch.parse(descriptor)
        val keys=mutableListOf<Int>()
        val result=registry.report("combined",byteArrayOf(2,1),touch,256,256,
            {fail("key report must not call touch");false},{_,usage,_->keys+=usage;true})
        assertTrue(result.accepted);assertFalse(result.touchMatched);assertEquals(listOf(0xb5),keys)
        val invalid=registry.report("combined",byteArrayOf(9,1),touch,256,256,
            {fail("invalid report");false},{_,_,_->fail("invalid report");false})
        assertFalse(invalid.recognized);assertFalse(invalid.accepted)
    }
}
