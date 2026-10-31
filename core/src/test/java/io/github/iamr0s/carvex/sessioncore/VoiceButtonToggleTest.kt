package io.github.iamr0s.carvex.sessioncore

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class VoiceButtonToggleTest {
    @Test fun actionTwoEnqueuesImmediatelyAndActionThreeOnlyRearms(){
        val voice=VoiceButtonToggle();var queued=0
        val enqueue={queued++;true}
        assertFalse(voice.button(0,enqueue))
        assertTrue(voice.button(3,enqueue));assertEquals(0,queued)
        assertTrue(voice.button(2,enqueue));assertTrue(voice.button(2,enqueue))
        assertEquals(1,queued)
        assertTrue(voice.button(3,enqueue));assertTrue(voice.button(3,enqueue))
        assertEquals(1,queued)
        assertTrue(voice.button(2,enqueue))
        assertEquals(2,queued)
        assertTrue(voice.button(3,enqueue));assertEquals(2,queued)
    }

    @Test fun aRejectedQueueAllowsActionTwoToRetryWithoutActionThree(){
        val voice=VoiceButtonToggle();var attempts=0
        assertFalse(voice.button(2){attempts++;false})
        assertTrue(voice.button(2){attempts++;true})
        assertTrue(voice.button(2){fail("adjacent accepted action 2");false})
        assertTrue(voice.button(3){fail("action 3 must not enqueue");false})
        assertEquals(2,attempts)
    }

    @Test fun acceptedTogglesAlternateOnActionTwoAndActionThreeNeverStops(){
        val voice=VoiceButtonToggle();val commands=mutableListOf<Boolean>()
        repeat(3){
            assertTrue(voice.button(2){voice.toggle{start->commands+=start;true}})
            assertEquals(it%2==0,voice.isRequestedActive)
            assertTrue(voice.button(2){fail("duplicate action 2");false})
            assertTrue(voice.button(3){fail("action 3 must not dispatch STOP");false})
            assertEquals(it%2==0,voice.isRequestedActive)
        }
        assertEquals(listOf(true,false,true),commands)
        assertTrue(voice.isRequestedActive)
        assertTrue(voice.button(3){fail("duplicate action 3");false})
        assertTrue(voice.isRequestedActive)
    }

    @Test fun requestedStateCommitsOnlyAfterAcceptedDispatch(){
        val voice=VoiceButtonToggle()
        assertFalse(voice.toggle{start->
            assertTrue(start);assertFalse(voice.isRequestedActive);false
        })
        assertFalse(voice.isRequestedActive)
        assertTrue(voice.toggle{start->
            assertTrue(start);assertFalse(voice.isRequestedActive);true
        })
        assertFalse(voice.toggle{start->
            assertFalse(start);assertTrue(voice.isRequestedActive);false
        })
        assertTrue(voice.isRequestedActive)
        assertTrue(voice.toggle{start->
            assertFalse(start);assertTrue(voice.isRequestedActive);true
        })
        assertFalse(voice.isRequestedActive)
    }

    @Test fun dispatchExceptionsKeepThePreviousRequestedState(){
        val voice=VoiceButtonToggle()
        assertThrows(IllegalStateException::class.java){voice.toggle{error("remote start failed")}}
        assertFalse(voice.isRequestedActive)
        assertTrue(voice.toggle{true})
        assertThrows(IllegalStateException::class.java){voice.toggle{error("remote stop failed")}}
        assertTrue(voice.isRequestedActive)
    }

    @Test fun dispatchDoesNotHoldTheStateLockAndCloseInvalidatesItsCompletion(){
        for(explicit in listOf(false,true)){
            val voice=VoiceButtonToggle();val entered=CountDownLatch(1);val finish=CountDownLatch(1)
            val result=AtomicBoolean(true)
            val dispatch:(Boolean)->Boolean={entered.countDown();check(finish.await(3,TimeUnit.SECONDS));true}
            val worker=Thread{
                result.set(if(explicit)voice.request(true,dispatch)else voice.toggle(dispatch))
            }.apply{isDaemon=true;start()}
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val closed=CountDownLatch(1)
            Thread{assertFalse(voice.isRequestedActive);voice.close();closed.countDown()}.apply{isDaemon=true;start()}
            try{assertTrue("query/close blocked behind remote dispatch",closed.await(3,TimeUnit.SECONDS))}
            finally{finish.countDown();worker.join(3000)}
            assertFalse(worker.isAlive);assertFalse(result.get())
            assertFalse(voice.isRequestedActive)
            assertFalse(voice.toggle{fail("closed");true})
            assertFalse(voice.request(true){fail("closed");true})
            assertFalse(voice.button(2){fail("closed");true})
        }
    }

    @Test fun anInFlightToggleCannotIssueASecondRemoteCall(){
        val voice=VoiceButtonToggle()
        assertTrue(voice.toggle{start->
            assertTrue(start)
            assertFalse(voice.toggle{fail("concurrent remote call");true})
            true
        })
        assertTrue(voice.isRequestedActive)
    }

    @Test fun queueExceptionCanRetryAndCloseDuringEnqueueCannotReviveButton(){
        val voice=VoiceButtonToggle()
        assertThrows(IllegalStateException::class.java){voice.button(2){error("queue failure")}}
        var retried=false
        assertTrue(voice.button(2){retried=true;true});assertTrue(retried)
        assertTrue(voice.button(3){fail("rearm must not enqueue");false})
        assertFalse(voice.button(2){voice.close();true})
        assertFalse(voice.button(2){fail("closed activation");true})
        assertFalse(voice.button(3){fail("closed rearm");true})
        assertFalse(voice.isRequestedActive)
    }

    @Test fun closeDuringAThrowingEnqueueCannotReviveButton(){
        val voice=VoiceButtonToggle()
        assertThrows(IllegalStateException::class.java){voice.button(2){voice.close();error("queue failure")}}
        assertFalse(voice.button(2){fail("closed activation");true})
        assertFalse(voice.button(3){fail("closed rearm");true})
        assertFalse(voice.isRequestedActive)
    }

    @Test fun anOldActivationCompletionCannotClearANewerActivation(){
        for(oldAccepted in listOf(false,true)){
            val voice=VoiceButtonToggle();val entered=CountDownLatch(1);val finish=CountDownLatch(1)
            val firstResult=AtomicBoolean(!oldAccepted)
            val firstActivation=Thread{
                firstResult.set(voice.button(2){entered.countDown();check(finish.await(3,TimeUnit.SECONDS));oldAccepted})
            }.apply{isDaemon=true;start()}
            var nextEnqueues=0
            try{
                assertTrue(entered.await(3,TimeUnit.SECONDS))
                assertTrue(voice.button(3){fail("action 3 must not enqueue");false})
                assertTrue(voice.button(2){nextEnqueues++;true})
                assertTrue(voice.button(2){fail("duplicate newer activation");false})
            }finally{finish.countDown();firstActivation.join(3000)}
            assertFalse(firstActivation.isAlive);assertEquals(oldAccepted,firstResult.get())
            assertTrue(voice.button(2){fail("old completion must retain the newer token");false})
            assertEquals(1,nextEnqueues)
            assertTrue(voice.button(3){fail("action 3 must not enqueue");false})
            assertTrue(voice.button(2){nextEnqueues++;true})
            assertEquals(2,nextEnqueues)
        }
    }

    @Test fun explicitCommandsShareToggleStateAndRepeatedCommandsStillDispatch(){
        val voice=VoiceButtonToggle();val commands=mutableListOf<Boolean>()
        val dispatch:(Boolean)->Boolean={commands+=it;true}
        assertTrue(voice.request(true,dispatch));assertTrue(voice.isRequestedActive)
        assertTrue(voice.request(true,dispatch));assertTrue(voice.isRequestedActive)
        assertTrue(voice.request(false,dispatch));assertFalse(voice.isRequestedActive)
        assertTrue(voice.toggle(dispatch));assertTrue(voice.isRequestedActive)
        assertEquals(listOf(true,true,false,true),commands)
    }

    @Test fun rejectedOrThrowingExplicitCommandsDoNotCommitRequestedState(){
        val voice=VoiceButtonToggle()
        assertFalse(voice.request(true){assertFalse(voice.isRequestedActive);false})
        assertFalse(voice.isRequestedActive)
        assertTrue(voice.request(true){true})
        assertFalse(voice.request(false){assertTrue(voice.isRequestedActive);false})
        assertThrows(IllegalStateException::class.java){voice.request(false){error("remote stop failed")}}
        assertTrue(voice.isRequestedActive)
        assertTrue(voice.toggle{start->assertFalse(start);true})
        assertFalse(voice.isRequestedActive)
    }
}
