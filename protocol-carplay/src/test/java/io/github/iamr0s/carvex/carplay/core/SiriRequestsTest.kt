package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class SiriRequestsTest {
    private fun request(seq:String?,action:Any)=RtspMessage("POST /command RTSP/1.0",
        if(seq==null)emptyMap()else mapOf("cseq" to seq),
        Plist.encode(mapOf("type" to "requestSiri","params" to mapOf("siriAction" to action))))
    @Test fun sameRequestReplaysButConflictingBodyNeverRunsAnotherAction(){
        val actions=mutableListOf<Int>();val handler=SiriRequests{actions.add(it);true}
        assertEquals(200,handler.handle(request("1",2)).first)
        assertEquals(200,handler.handle(request("1",2)).first)
        assertEquals(409,handler.handle(request("1",3)).first)
        assertEquals(listOf(2),actions)
        assertEquals(200,handler.handle(request("2",3)).first)
        assertEquals(listOf(2,3),actions)
        handler.reset()
        assertEquals(200,handler.handle(request("1",3)).first)
        assertEquals(listOf(2,3,3),actions)
    }
    @Test fun eachConnectionOwnsItsSequenceAndBadTypesDoNotDispatch(){
        var called=0
        val control=SiriRequests{called++;true};val event=SiriRequests{called++;true}
        assertEquals(200,control.handle(request("1",2)).first)
        assertEquals(200,event.handle(request("1",2)).first)
        assertEquals(2,called)
        for((i,value) in listOf("2",0,1,2.0).withIndex())assertEquals(501,event.handle(request((i+10).toString(),value)).first)
        assertEquals(400,event.handle(request(null,2)).first)
        assertEquals(400,event.handle(request("bad",2)).first)
        assertEquals(2,called)
    }

    @Test fun missingSequenceCompatibilityDispatchesWithoutCreatingAReplayKey(){
        val actions=mutableListOf<Int>()
        val event=SiriRequests(allowMissingCSeq=true){actions+=it;true}
        val unsequenced=request(null,2)
        assertEquals(200,event.handle(unsequenced).first)
        assertEquals(200,event.handle(unsequenced).first)
        assertEquals(listOf(2,2),actions)
        // Neither common synthetic sequence can have been reserved by the absent header.
        assertEquals(200,event.handle(request("0",3)).first)
        assertEquals(200,event.handle(request("1",3)).first)
        assertEquals(200,event.handle(request(null,2)).first)
        assertEquals(200,event.handle(request("0",3)).first)
        assertEquals(200,event.handle(request("1",3)).first)
        assertEquals(409,event.handle(request("0",2)).first)
        assertEquals(listOf(2,2,3,3,2),actions)
        assertFalse(unsequenced.headers.containsKey("cseq"))
    }

    @Test fun presentMalformedSequenceIsRejectedEvenWhenMissingIsAllowed(){
        var called=0
        val event=SiriRequests(allowMissingCSeq=true){called++;true}
        for(seq in listOf("","bad"," ","-1","1.0","12345678901"))
            assertEquals("CSeq=$seq",400,event.handle(request(seq,2)).first)
        assertEquals(0,called)
        assertEquals(200,event.handle(request(null,2)).first)
        assertEquals(1,called)
    }

    @Test fun strictDefaultAndCommandValidationRemainRequired(){
        var called=0
        val strict=SiriRequests{called++;true}
        val event=SiriRequests(allowMissingCSeq=true){called++;true}
        assertEquals(400,strict.handle(request(null,2)).first)
        for(value in listOf("2",0,1,2.0))assertEquals(501,event.handle(request(null,value)).first)
        val body=request(null,2).body
        assertEquals(400,event.handle(RtspMessage("GET /command RTSP/1.0",emptyMap(),body)).first)
        assertEquals(400,event.handle(RtspMessage("POST /feedback RTSP/1.0",emptyMap(),body)).first)
        assertEquals(400,event.handle(RtspMessage("POST /command RTSP/1.0",emptyMap(),byteArrayOf())).first)
        assertEquals(0,called)
    }
}
