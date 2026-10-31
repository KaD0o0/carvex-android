package io.github.iamr0s.carvex.session

import io.github.iamr0s.carvex.sessioncore.VoiceButtonToggle
import io.github.iamr0s.carvex.carplay.core.Plist
import io.github.iamr0s.carvex.carplay.core.RtspMessage
import io.github.iamr0s.carvex.carplay.core.SiriRequests
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual plist parser and per-connection CSeq replay filter through the product
 * button coordinator. The queue and CarWith endpoint are fakes; no Android Binder is needed. */
class VoiceButtonPipelineTest {
    private companion object {
        /** 测试用语义码：与实际宿主码值无关，只验证"开始/结束"两条路径。 */
        const val VOICE_START=8107
        const val VOICE_STOP=8108
    }
    private class Pipeline(allowMissingCSeq:Boolean=false) {
        val voice=VoiceButtonToggle()
        val queue=ArrayDeque<Boolean?>()
        val delivered=mutableListOf<Pair<Int,Boolean>>()
        private val requests=SiriRequests(allowMissingCSeq){action->voice.button(action){queue.addLast(null);true}}

        fun handle(cseq:String?,action:Any):Int=requests.handle(RtspMessage(
            "POST /command RTSP/1.0",if(cseq==null)emptyMap()else mapOf("cseq" to cseq),
            Plist.encode(mapOf("type" to "requestSiri","params" to mapOf("siriAction" to action))))).first

        fun drain(downAccepted:Boolean=true){
            while(queue.isNotEmpty()){
                val requested=queue.removeFirst()
                val dispatch:(Boolean)->Boolean={start->
                    // 测试固定两个语义码：协议侧只关心"语音开始/结束"，具体码值由宿主决定。
                    val code=if(start)VOICE_START else VOICE_STOP
                    delivered+=code to true
                    if(downAccepted)delivered+=code to false
                    downAccepted
                }
                if(requested==null)voice.toggle(dispatch)else voice.request(requested,dispatch)
            }
        }
    }

    @Test fun aSingleActionTwoStartsBeforeActionThreeAndReplaysCannotRunItAgain(){
        val path=Pipeline()
        assertEquals(200,path.handle("1",2))
        assertEquals(200,path.handle("1",2)) // Same request: replayed response, no second callback.
        assertEquals(200,path.handle("2",2)) // A new request with adjacent action 2 is suppressed.
        assertEquals(1,path.queue.size);assertTrue(path.delivered.isEmpty())
        path.drain() // No action 3 has arrived: the original single action 2 must already start.
        assertEquals(listOf(8107 to true,8107 to false),path.delivered)
        assertTrue(path.voice.isRequestedActive)

        assertEquals(409,path.handle("2",3)) // A conflicting body cannot rearm the input adapter.
        assertTrue(path.queue.isEmpty())
        assertEquals(200,path.handle("10",2));assertTrue(path.queue.isEmpty())
        assertEquals(200,path.handle("3",3))
        assertEquals(200,path.handle("3",3))
        assertEquals(200,path.handle("4",3))
        assertTrue(path.queue.isEmpty()) // Action 3 never queues a stop or a second toggle.
        path.drain()
        assertEquals(listOf(8107 to true,8107 to false),path.delivered)
        assertTrue(path.voice.isRequestedActive)

        assertEquals(200,path.handle("5",2));assertEquals(1,path.queue.size)
        path.drain()
        assertEquals(listOf(8107 to true,8107 to false,8108 to true,8108 to false),path.delivered)
        assertFalse(path.voice.isRequestedActive)

        assertEquals(200,path.handle("3",3)) // An older cached request cannot rearm the input.
        assertEquals(200,path.handle("11",2));assertTrue(path.queue.isEmpty())
        assertEquals(200,path.handle("6",3))
        assertEquals(200,path.handle("7",3))
        assertEquals(501,path.handle("8","2")) // The real parser rejects a string action.
        assertEquals(200,path.handle("9",3))
        assertTrue(path.queue.isEmpty());assertEquals(4,path.delivered.size)
    }

    @Test fun eventCompatibilityWithoutCseqStartsImmediatelyAndSuppressesAdjacentActionTwo(){
        val strict=Pipeline()
        assertEquals(400,strict.handle(null,2));assertTrue(strict.queue.isEmpty())
        val event=Pipeline(allowMissingCSeq=true)
        assertEquals(200,event.handle(null,2));assertEquals(1,event.queue.size)
        assertEquals(200,event.handle(null,2));assertEquals(1,event.queue.size)
        event.drain()
        assertEquals(listOf(8107 to true,8107 to false),event.delivered)
        assertEquals(200,event.handle(null,3));assertTrue(event.queue.isEmpty())
        assertEquals(200,event.handle(null,2));event.drain()
        assertEquals(listOf(8107 to true,8107 to false,8108 to true,8108 to false),event.delivered)
    }

    @Test fun rejectedCarWithStartDoesNotTurnTheNextClickIntoAStop(){
        val path=Pipeline()
        assertEquals(200,path.handle("1",2));assertEquals(200,path.handle("2",3))
        path.drain(downAccepted=false)
        assertEquals(listOf(8107 to true),path.delivered)
        assertFalse(path.voice.isRequestedActive)

        assertEquals(200,path.handle("3",2));assertEquals(200,path.handle("4",3))
        path.drain()
        assertEquals(listOf(8107 to true,8107 to true,8107 to false),path.delivered)
        assertTrue(path.voice.isRequestedActive)
    }

    @Test fun manualStopAndParsedSiriClickShareTheSameOrderedVoiceState(){
        val path=Pipeline()
        path.queue.addLast(true) // Manual 8107 command.
        assertEquals(200,path.handle("1",2));assertEquals(200,path.handle("2",3))
        path.queue.addLast(false) // Manual 8108 command.
        assertEquals(200,path.handle("3",2));assertEquals(200,path.handle("4",3))
        path.drain()
        assertEquals(listOf(8107 to true,8107 to false,8108 to true,8108 to false,
            8108 to true,8108 to false,8107 to true,8107 to false),path.delivered)
        assertTrue(path.voice.isRequestedActive)
    }
}
