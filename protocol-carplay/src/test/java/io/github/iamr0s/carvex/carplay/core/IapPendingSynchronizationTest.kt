package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** In-memory peers, no Apple execution or real-device compatibility claim.
 * Pending active defaults deliberately differ from both our local offer and
 * the peer proposal. Reads are bounded and only IapLink reads its input. */
class IapPendingSynchronizationTest {
    private fun payload(link:Int=1,id:Int=10,extensions:ByteArray=byteArrayOf(),zero:Boolean=false):ByteArray {
        val fixed=byteArrayOf(link.toByte(),if(zero)0 else 5)+IapWire.be16(2048)+
            IapWire.be16(if(zero)0 else 20)+IapWire.be16(if(zero)0 else 10)+
            byteArrayOf(if(zero)0 else 1,0)
        return fixed+(if(link==1)byteArrayOf() else
            byteArrayOf(extensions.size.toByte())+extensions+byteArrayOf(1))+byteArrayOf(id.toByte(),0,2)
    }
    private class Peer(private val failWrite:Int=0):AutoCloseable {
        val input=BoundedByteInput()
        val packets=LinkedBlockingQueue<ByteArray>()
        val failures=CopyOnWriteArrayList<Throwable>()
        val failed=CountDownLatch(1)
        val writes=AtomicInteger()
        val executor=Executors.newSingleThreadExecutor()
        val link=IapLink(input,object:OutputStream(){
            override fun write(b:Int)=write(byteArrayOf(b.toByte()),0,1)
            override fun write(b:ByteArray,off:Int,len:Int){
                if(writes.incrementAndGet()==failWrite)throw IOException("synthetic SYNACK output failure")
                packets.add(b.copyOfRange(off,off+len))
            }
        },{},{failures.add(it);failed.countDown()},localRetransmitTimeoutMs=20,localMaxRetransmissions=1)
        fun put(f:IapWire.Frame,version:Int=1){check(input.offer(IapWire.packet(f,version)))}
        fun bytes(timeoutMs:Long=3000):ByteArray = checkNotNull(packets.poll(timeoutMs,TimeUnit.MILLISECONDS)){"no packet"}
        fun read(version:Int=1)=IapWire.read(ByteArrayInputStream(bytes()),packetVersion=version)
        fun quiet(millis:Long=1200){assertNull("unexpected output after transition",packets.poll(millis,TimeUnit.MILLISECONDS))}
        fun begin(p:ByteArray,seq:Int=255):Pair<Future<*>,IapWire.Frame>{
            val future=executor.submit{link.start(allowDirectSyn=true,detectOnIdle=false)}
            put(IapWire.Frame(0x80,seq,0,0,p))
            return future to read()
        }
        fun confirm(future:Future<*>,response:IapWire.Frame){
            put(IapWire.Frame(0x40,response.ack,response.seq,0,byteArrayOf()))
            future.get(3,TimeUnit.SECONDS)
        }
        fun failure(future:Future<*>):Throwable {
            val thrown=assertThrows(ExecutionException::class.java){future.get(4,TimeUnit.SECONDS)}
            assertTrue(failed.await(2,TimeUnit.SECONDS))
            assertEquals(1,failures.size)
            assertNull(link.negotiatedControlSession)
            assertNull(link.negotiatedSynchronizationSchema)
            return checkNotNull(thrown.cause)
        }
        override fun close(){link.close();input.close();executor.shutdownNow()}
    }

    @Test(timeout=8000) fun freshPendingSynResetsEpochAndNegotiatesNewSessionAcrossPeerSequenceWrap(){
        Peer().use{p->
            val (started,first)=p.begin(payload(link=3,id=10),255)
            val epoch=p.link.linkEpoch
            val invalidated=CountDownLatch(1)
            p.link.bindEpoch(epoch){invalidated.countDown()}.use{
                p.put(IapWire.Frame(0x80,0,0,0,payload(link=2,id=7)))
                val second=p.read()
                assertTrue(invalidated.await(1,TimeUnit.SECONDS))
                assertTrue(p.link.linkEpoch>epoch)
                assertNull(p.link.negotiatedControlSession)
                assertEquals(0,p.link.generation)
                assertEquals((first.seq+1) and 255,second.seq)
                assertEquals(0,second.ack)
                assertEquals(2,second.payload[0].toInt())
                p.confirm(started,second)
                assertEquals(IapLink.ControlSession(7,0,2),p.link.negotiatedControlSession)
                assertEquals(1,p.link.generation)
                p.put(IapWire.Frame(0x40,1,second.seq,7,IapWire.csm(0x5703)),2)
                assertEquals(1,p.read(2).ack)
                assertEquals(0x5703,p.link.poll(1000)?.id)
                p.quiet()
            }
        }
    }

    @Test(timeout=8000) fun acknowledgementForSupersededSynAckCannotCommitNewRound(){
        Peer().use{p->
            val (started,first)=p.begin(payload())
            p.put(IapWire.Frame(0x80,0,0,0,payload()))
            val second=p.read()
            p.put(IapWire.Frame(0x40,second.ack,first.seq,0,byteArrayOf()))
            assertEquals("Unassociated SYN ACK",p.failure(started).message)
            assertEquals(0,p.link.generation)
            p.quiet()
        }
    }

    @Test(timeout=8000) fun malformedFreshSynDoesNotReceiveReplacementSynAck(){
        Peer().use{p->
            val (started,_)=p.begin(payload())
            p.put(IapWire.Frame(0x80,0,0,0,payload(link=3,extensions=byteArrayOf(0x54))))
            assertEquals("Invalid SYN extension set",p.failure(started).message)
            p.quiet()
        }
    }

    @Test(timeout=8000) fun pendingTimerUsesActiveDefaultsAndResendsExactlyTheSameFrame(){
        Peer().use{p->
            val (started,response)=p.begin(payload())
            assertEquals(20,IapWire.u16(response.payload,4)) // tentative local/peer agreement
            p.quiet(200) // not the proposed 20ms
            val retry=p.bytes()
            assertArrayEquals(IapWire.packet(response),retry)
            assertNull(p.link.negotiatedControlSession)
            assertEquals(0,p.link.generation)
            p.confirm(started,response)
            p.quiet()
        }
    }

    @Test(timeout=8000) fun zeroRetransmitProposalDoesNotDisablePendingActiveReliability(){
        Peer().use{p->
            val (started,response)=p.begin(payload(zero=true))
            assertEquals(0,IapWire.u16(response.payload,4))
            assertArrayEquals(IapWire.packet(response),p.bytes())
            p.confirm(started,response)
            p.link.send(0xaa00) // only AFTER commit is data no-retransmit
            assertEquals(10,p.read().session)
            p.quiet()
        }
    }

    @Test(timeout=10000) fun timerAndDuplicatesShareBudgetThenSilenceTerminatesLinkOnce(){
        Peer().use{p->
            val syn=IapWire.Frame(0x80,255,0,0,payload())
            val (started,response)=p.begin(syn.payload)
            repeat(29){
                p.put(syn)
                assertArrayEquals(IapWire.packet(response),p.bytes())
            }
            // Timer spends retry30; its next expiry must fail, not output retry31.
            assertArrayEquals(IapWire.packet(response),p.bytes())
            val cause=p.failure(started)
            assertTrue(cause is SocketTimeoutException)
            assertTrue(cause.message!!.contains("retries=30"))
            p.quiet()
            assertEquals(31,p.writes.get()) // initial + 30 retries
        }
    }

    @Test(timeout=8000) fun freshPendingSynDoesNotRefillResendBudgetOrSpendItAsAProposal(){
        Peer().use{p->
            val syn=IapWire.Frame(0x80,255,0,0,payload())
            val (started,first)=p.begin(syn.payload)
            repeat(30){p.put(syn);assertArrayEquals(IapWire.packet(first),p.bytes())}
            val replacement=IapWire.Frame(0x80,0,0,0,payload(id=7))
            p.put(replacement)
            assertEquals(0,p.read().ack) // new-proposal counter is independent
            p.put(replacement)
            assertTrue(p.failure(started) is SocketTimeoutException)
            p.quiet()
            assertEquals(32,p.writes.get())
        }
    }

    @Test(timeout=8000) fun endlessFreshSynsHaveSeparateBoundedProposalBudget(){
        Peer().use{p->
            val (started,_)=p.begin(payload(),0)
            for(seq in 1..10){
                p.put(IapWire.Frame(0x80,seq,0,0,payload()))
                assertEquals(seq,p.read().ack)
            }
            assertEquals("iAP SYN proposal budget exhausted; link dead",p.failure(started).message)
            assertEquals(11,p.writes.get())
            p.quiet()
        }
    }

    @Test(timeout=8000) fun detectionCancelsOldTimerBeforeAnyReplacementSynArrives(){
        Peer().use{p->
            val (started,_)=p.begin(payload())
            val epoch=p.link.linkEpoch
            check(p.input.offer(IapWire.detection))
            assertArrayEquals(IapWire.detection,p.bytes())
            assertTrue(p.link.linkEpoch>epoch)
            p.quiet()
            p.put(IapWire.Frame(0x80,9,0,0,payload(id=7)))
            val replacement=p.read()
            p.confirm(started,replacement)
            assertEquals(7,p.link.negotiatedControlSession!!.id)
            p.quiet()
        }
    }

    @Test(timeout=8000) fun partialFinalAckDoesNotStopTimerOrIntroduceAnotherInputReader(){
        Peer().use{p->
            val (started,response)=p.begin(payload())
            val ack=IapWire.packet(IapWire.Frame(0x40,255,response.seq,0,byteArrayOf()))
            check(p.input.offer(ack.copyOfRange(0,4)))
            assertArrayEquals(IapWire.packet(response),p.bytes())
            check(p.input.offer(ack.copyOfRange(4,ack.size)))
            started.get(3,TimeUnit.SECONDS)
            assertEquals(1,p.link.generation)
            p.quiet()
        }
    }

    @Test(timeout=8000) fun matchingSecondSynAckKeepsPendingUntilPureConfirmation(){
        Peer().use{p->
            val (started,response)=p.begin(payload())
            p.put(IapWire.Frame(0xc0,17,response.seq,0,payload()))
            val second=p.read()
            assertEquals(0xc0,second.flags)
            assertNull(p.link.negotiatedControlSession)
            p.confirm(started,second)
            assertEquals(1,p.link.generation)
            p.quiet()
        }
    }

    @Test(timeout=8000) fun outputFailureOnTimerClosesReaderAndReportsOriginalFailureOnce(){
        Peer(failWrite=2).use{p->
            val (started,_)=p.begin(payload())
            assertEquals("synthetic SYNACK output failure",p.failure(started).message)
            p.quiet()
            assertEquals(2,p.writes.get())
        }
    }

    @Test(timeout=8000) fun callerCloseCancelsPendingTimerWithoutReportingProtocolFailure(){
        Peer().use{p->
            val (started,_)=p.begin(payload())
            p.link.close()
            assertThrows(ExecutionException::class.java){started.get(3,TimeUnit.SECONDS)}
            p.quiet()
            assertTrue(p.failures.isEmpty())
        }
    }

    @Test(timeout=8000) fun invalidExtensionSetsNeverProduceSynAckForLinkTwoOrThree(){
        val invalid=listOf(byteArrayOf(0x7f),byteArrayOf(0x54),byteArrayOf(0x55),
            byteArrayOf(0x54,0x54),byteArrayOf(0x55,0x55),
            byteArrayOf(0x54,0x54,0x55,0x7f),byteArrayOf(0x54,0x55,0x7f),byteArrayOf(0))
        for(version in 2..3)for(extensions in invalid)Peer().use{p->
            val started=p.executor.submit{p.link.start(allowDirectSyn=true,detectOnIdle=false)}
            p.put(IapWire.Frame(0x80,255,0,0,payload(link=version,extensions=extensions)))
            assertEquals("Invalid SYN extension set",p.failure(started).message)
            assertEquals(0,p.writes.get())
        }
    }

    @Test(timeout=8000) fun validExtensionSetsAreAcceptedWithoutAdvertisingUnimplementedFeatures(){
        // Native 0x1000d4ee0 folds feature bits with OR: multiplicity and order
        // do not matter, but both known opcodes must be present in a nonempty offer.
        val valid=listOf(byteArrayOf(),byteArrayOf(0x54,0x55),byteArrayOf(0x55,0x54),
            byteArrayOf(0x54,0x54,0x55),byteArrayOf(0x55,0x54,0x55),
            byteArrayOf(0x54,0x55,0x54,0x55),byteArrayOf(0x55,0x54,0x55,0x54,0x55))
        for(version in 2..3)for(extensions in valid)Peer().use{p->
            val (started,response)=p.begin(payload(link=version,extensions=extensions))
            assertEquals(0,response.payload[10].toInt()) // local feature count remains zero
            p.confirm(started,response)
            assertEquals(1,p.link.generation)
        }
    }

    @Test(timeout=8000) fun duplicateFeatureAcceptanceDoesNotBypassCountOrLengthChecks(){
        for(version in 2..3){
            val cases=listOf(
                payload(link=version,extensions=byteArrayOf(0x54,0x55,0x54,0x55,0x54,0x55)) to
                    "Unsupported SYN extension count 6",
                payload(link=version).copyOf(12).also{it[10]=5} to "Truncated SYN extension bytes",
                payload(link=version,extensions=byteArrayOf(0x54,0x54,0x55)).dropLast(1).toByteArray() to
                    "SYN session list length mismatch")
            for((body,message) in cases)Peer().use{p->
                val started=p.executor.submit{p.link.start(allowDirectSyn=true,detectOnIdle=false)}
                p.put(IapWire.Frame(0x80,255,0,0,body))
                assertEquals(message,p.failure(started).message)
                assertEquals(0,p.writes.get())
            }
        }
    }

    @Test(timeout=8000) fun equivalentFeatureMultiplicityInSecondSynAckRemainsValidWhilePending(){
        for(version in 2..3)Peer().use{p->
            val (started,response)=p.begin(payload(link=version,extensions=byteArrayOf(0x54,0x54,0x55)))
            assertEquals(0,response.payload[10].toInt())
            p.put(IapWire.Frame(0xc0,17,response.seq,0,payload(link=version,extensions=byteArrayOf(0x55,0x54))))
            val second=p.read()
            assertEquals(0xc0,second.flags)
            assertEquals(0,second.payload[10].toInt())
            assertNull(p.link.negotiatedControlSession)
            p.confirm(started,second)
            assertEquals(IapLink.ControlSession(10,0,2),p.link.negotiatedControlSession)
            assertEquals(1,p.link.generation)
        }
    }

    @Test(timeout=8000) fun freshPendingSynAcceptsDuplicatePairedFeaturesAndPreservesSessionOffset(){
        for(version in 2..3)Peer().use{p->
            val (started,first)=p.begin(payload(link=version))
            p.put(IapWire.Frame(0x80,0,0,0,payload(link=version,id=7,
                extensions=byteArrayOf(0x54,0x55,0x54,0x55,0x54))))
            val response=p.read()
            assertEquals((first.seq+1) and 255,response.seq)
            assertEquals(0,response.payload[10].toInt())
            p.confirm(started,response)
            assertEquals(IapLink.ControlSession(7,0,2),p.link.negotiatedControlSession)
            assertEquals(1,p.link.generation)
        }
    }

    @Test(timeout=8000) fun changedValidExtensionSetGetsCapabilityBoundedCounterproposal(){
        Peer().use{p->
            val (started,response)=p.begin(payload(link=3))
            p.put(IapWire.Frame(0xc0,17,response.seq,0,payload(link=3,extensions=byteArrayOf(0x54,0x55))))
            val second=p.read()
            assertEquals(0xc0,second.flags)
            assertEquals(0,second.payload[10].toInt())
            assertEquals(0,p.link.generation)
            p.confirm(started,second)
            p.quiet()
        }
    }
}
