package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.*

/** In-memory reproduction of the FRONTLANDER pending SYNACK PSN27/ACK16 gap.
 * Original-instruction oracle: research/connection/tools/probe_iap2_pending_ack.py.
 * No vehicle/interoperability claim. Sequence numbers are read from the real offer. */
class IapPendingAckRecoveryTest {
    private fun syn(seq:Int=105,version:Int=1,id:Int=10,zero:Boolean=false)=IapWire.Frame(0x80,seq,0,0,
        byteArrayOf(version.toByte(),5)+IapWire.be16(2048)+IapWire.be16(if(zero)0 else 1500)+
            IapWire.be16(if(zero)0 else 73)+byteArrayOf(if(zero)0 else 30,if(zero)0 else 3)+
            (if(version==1)byteArrayOf() else byteArrayOf(0,1))+byteArrayOf(id.toByte(),0,2))

    private class Peer(detectMs:Int=2500,private val failReset:Boolean=false):AutoCloseable {
        val input=BoundedByteInput()
        val packets=LinkedBlockingQueue<ByteArray>()
        val logs=LinkedBlockingQueue<String>()
        val failures=CopyOnWriteArrayList<Throwable>()
        val failed=CountDownLatch(1)
        private val executor=Executors.newSingleThreadExecutor()
        val link=IapLink(input,object:OutputStream(){
            override fun write(b:Int)=write(byteArrayOf(b.toByte()),0,1)
            override fun write(b:ByteArray,off:Int,len:Int){
                val bytes=b.copyOfRange(off,off+len)
                if(failReset&&bytes.size>=5&&bytes[4]==0x10.toByte())throw IOException("synthetic RST write failure")
                packets.add(bytes)
            }
        },{logs.add(it)},{failures.add(it);failed.countDown()},firstByteTimeoutMs=0,detectTimeoutMs=detectMs)
        fun start():Future<*> = executor.submit{link.start(allowDirectSyn=true,detectOnIdle=false)}
        fun put(frame:IapWire.Frame,version:Int=1){check(input.offer(IapWire.packet(frame,version)))}
        fun read():IapWire.Frame=IapWire.read(ByteArrayInputStream(checkNotNull(packets.poll(2,TimeUnit.SECONDS)){"no output; failures=$failures"}))
        fun ack(response:IapWire.Frame,gap:Int=0)=IapWire.Frame(0x40,response.ack,(response.seq-gap) and 255,0,byteArrayOf())
        fun assertReset(){
            val reset=read()
            assertEquals(0x10,reset.flags);assertEquals(0,reset.seq)
            assertEquals(0,reset.ack);assertEquals(0,reset.session)
            assertTrue(reset.payload.isEmpty())
            assertNull(link.negotiatedControlSession)
            assertNull(link.negotiatedSynchronizationSchema)
            assertEquals(0,link.generation)
            assertTrue(failures.isEmpty())
        }
        fun confirm(started:Future<*>,response:IapWire.Frame){put(ack(response));started.get(2,TimeUnit.SECONDS)}
        fun failure(started:Future<*>):Throwable {
            val cause=assertThrows(ExecutionException::class.java){started.get(3,TimeUnit.SECONDS)}.cause!!
            assertTrue(failed.await(1,TimeUnit.SECONDS));assertEquals(1,failures.size)
            assertNull(link.negotiatedControlSession)
            return cause
        }
        override fun close(){link.close();input.close();executor.shutdownNow()}
    }

    @Test(timeout=6000) fun loggedAckGapResetsSameLinkAndOnlyFreshHandshakePublishesReady(){
        Peer().use{p->
            val started=p.start();p.put(syn());val first=p.read()
            val invalidated=CountDownLatch(1)
            p.link.bindEpoch(p.link.linkEpoch){invalidated.countDown()}.use{
                p.put(p.ack(first,11));p.assertReset()
                assertTrue(invalidated.await(1,TimeUnit.SECONDS))
                assertFalse(started.isDone)
                // Late confirmation of the abandoned proposal is NOT a new handshake.
                p.put(p.ack(first))
                assertNull(p.packets.poll(80,TimeUnit.MILLISECONDS))
                assertNull(p.link.negotiatedControlSession)
                p.put(syn(seq=106,id=7));val replacement=p.read()
                assertEquals(0xc0,replacement.flags);assertEquals(106,replacement.ack)
                p.confirm(started,replacement)
                assertEquals(IapLink.ControlSession(7,0,2),p.link.negotiatedControlSession)
                assertEquals(1,p.link.generation)
                // Old SYNACK timer must not write after the new connection commits.
                assertNull(p.packets.poll(1100,TimeUnit.MILLISECONDS))
                assertTrue(p.failures.isEmpty())
            }
        }
    }

    @Test(timeout=5000) fun resetWaitIsBoundedWhenNoNewSynArrives(){
        Peer(detectMs=180).use{p->
            val started=p.start();p.put(syn());val first=p.read()
            p.put(p.ack(first,11));p.assertReset()
            assertTrue(p.failure(started) is SocketTimeoutException)
            assertTrue(p.failures.single().message!!.contains("NO_SYN"))
            assertNull(p.packets.poll(1100,TimeUnit.MILLISECONDS))
        }
    }

    @Test(timeout=6000) fun unsignedWindowBoundaryResetsOnlyOutOfWindowPureAcks(){
        for(gap in listOf(0,1,2,255))Peer().use{p->
            val started=p.start();p.put(syn());val first=p.read()
            p.put(p.ack(first,gap))
            when(gap){
                0->{started.get(2,TimeUnit.SECONDS);assertEquals(1,p.link.generation)}
                // Native accepts active-window gap1 in the bounded oracle, but
                // enabling it would change readiness semantics: explicitly out of scope.
                1->assertEquals("Unassociated SYN ACK",p.failure(started).message)
                else->{p.assertReset();p.put(syn(seq=106));p.confirm(started,p.read())}
            }
        }
    }

    @Test(timeout=6000) fun tentativeVersionAndZeroRetransmissionModeDoNotBypassActiveDefaultWindow(){
        for(version in 1..3)for(zero in listOf(false,true))Peer().use{p->
            val started=p.start();p.put(syn(version=version,zero=zero));val first=p.read()
            p.put(p.ack(first,11));p.assertReset()
            // The reset drops negotiation work, so a fresh reliable v1 offer
            // after a tentative zero mode/v3 is not a forbidden mid-round switch.
            p.put(syn(seq=106,version=1,id=7));val replacement=p.read()
            assertEquals(1,replacement.payload[0].toInt())
            assertEquals(1000,IapWire.u16(replacement.payload,4))
            p.confirm(started,replacement)
            assertEquals(IapLink.ControlSession(7,0,2),p.link.negotiatedControlSession)
        }
    }

    @Test(timeout=6000) fun oldSynAckTimerCannotRunWhileWaitingForReplacementSyn(){
        Peer().use{p->
            val started=p.start();p.put(syn());val first=p.read()
            p.put(p.ack(first,11));p.assertReset()
            assertNull(p.packets.poll(1150,TimeUnit.MILLISECONDS))
            assertFalse(started.isDone);assertTrue(p.failures.isEmpty())
            p.put(syn(seq=106));p.confirm(started,p.read())
        }
    }

    @Test(timeout=5000) fun repeatedRecoveryDoesNotRefillTheProposalBudget(){
        Peer().use{p->
            val started=p.start()
            repeat(10){round->
                p.put(syn(seq=round));val response=p.read()
                p.put(p.ack(response,11));p.assertReset()
            }
            p.put(syn(seq=11));assertEquals(0xc0,p.read().flags)
            assertTrue(p.failure(started).message!!.contains("proposal budget exhausted"))
            assertNull(p.packets.poll(1100,TimeUnit.MILLISECONDS))
        }
    }

    @Test(timeout=5000) fun recoveryDoesNotRefillTheSharedRetransmissionBudget(){
        Peer().use{p->
            val started=p.start();val original=syn()
            p.put(original);val first=p.read()
            repeat(29){p.put(original);assertEquals(first.seq,p.read().seq)}
            p.put(p.ack(first,11));p.assertReset()
            val fresh=syn(seq=106)
            p.put(fresh);val replacement=p.read()
            // The 30th resend remains available, but reset must not grant 30 more.
            p.put(fresh);assertEquals(replacement.seq,p.read().seq)
            p.put(fresh)
            assertTrue(p.failure(started).message!!.contains("retransmission budget exhausted"))
            assertNull(p.packets.poll(1100,TimeUnit.MILLISECONDS))
            assertEquals(0,p.link.generation)
        }
    }

    @Test(timeout=5000) fun resetWriteFailureTerminatesOnceWithoutPretendingToRecover(){
        Peer(failReset=true).use{p->
            val started=p.start();p.put(syn());val first=p.read()
            p.put(p.ack(first,11))
            assertEquals("synthetic RST write failure",p.failure(started).message)
            assertEquals(0,p.link.generation)
            assertNull(p.packets.poll(1100,TimeUnit.MILLISECONDS))
        }
    }

    @Test(timeout=5000) fun malformedReplacementSynStillFailsAndNeverPublishesReady(){
        Peer().use{p->
            val started=p.start();p.put(syn());val first=p.read()
            p.put(p.ack(first,11));p.assertReset()
            p.put(syn(seq=106).copy(payload=byteArrayOf(1)))
            assertTrue(p.failure(started) is IllegalArgumentException)
            assertNull(p.packets.poll());assertEquals(0,p.link.generation)
        }
    }

    @Test(timeout=5000) fun localCloseDuringRecoveryStopsTimerWithoutFailureCallback(){
        Peer(detectMs=180).use{p->
            val started=p.start();p.put(syn());val first=p.read()
            p.put(p.ack(first,11));p.assertReset();p.link.close()
            assertThrows(ExecutionException::class.java){started.get(2,TimeUnit.SECONDS)}
            assertNull(p.packets.poll(1100,TimeUnit.MILLISECONDS))
            assertTrue(p.failures.isEmpty());assertEquals(0,p.link.generation)
        }
    }

    @Test(timeout=5000) fun otherPendingPacketFormsRetainTheirExistingFailureGuards(){
        for(kind in listOf("data","session","peer-sequence","synack"))Peer().use{p->
            val started=p.start();p.put(syn());val first=p.read()
            val bad=p.ack(first,11)
            p.put(when(kind){
                "data"->bad.copy(payload=IapWire.csm(0x5703))
                "session"->bad.copy(session=10)
                "peer-sequence"->bad.copy(seq=(bad.seq+1) and 255)
                else->bad.copy(flags=0xc0,payload=first.payload)
            })
            assertTrue(p.failure(started) is IllegalArgumentException)
            assertNull(p.packets.poll());assertEquals(0,p.link.generation)
        }
    }

    @Test(timeout=5000) fun recoveryIsNotSilentlyEnabledForPostConnectionResynchronization(){
        Peer().use{p->
            val started=p.start();p.put(syn());p.confirm(started,p.read())
            assertEquals(1,p.link.generation)
            p.put(syn(seq=106));val next=p.read()
            p.put(p.ack(next,11))
            assertTrue(p.failed.await(2,TimeUnit.SECONDS))
            assertEquals("Unassociated SYN ACK",p.failures.single().message)
            assertNull(p.packets.poll());assertNull(p.link.negotiatedControlSession)
        }
    }
}
