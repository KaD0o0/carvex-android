package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** 24A446 ch78: pending SYNACK updates work; only a final associated pure ACK
 * commits. Synthetic peers exercise our capability-bounded implementation. */
class IapRenegotiationTest {
    private fun body(link:Int=1,retry:Int=2000,ack:Int=500,retries:Int=30,maxAck:Int=3,
        window:Int=5,mtu:Int=2048,id:Int=10,controlVersion:Int=2,
        extra:ByteArray=byteArrayOf(),extensions:ByteArray=byteArrayOf(),zero:Boolean=false):ByteArray =
        byteArrayOf(link.toByte(),window.toByte())+IapWire.be16(mtu)+IapWire.be16(if(zero)0 else retry)+
            IapWire.be16(if(zero)0 else ack)+byteArrayOf(if(zero)0 else retries.toByte(),if(zero)0 else maxAck.toByte())+
            (if(link==1)byteArrayOf() else byteArrayOf(extensions.size.toByte())+extensions+byteArrayOf((1+extra.size/3).toByte()))+
            byteArrayOf(id.toByte(),0,controlVersion.toByte())+extra
    private class Peer(val failWrite:Int=0,val localTx:Int=4096,val localAck:Int=10,
        val localMaxAck:Int=0,val localWindow:Int=1):AutoCloseable {
        val input=BoundedByteInput()
        val packets=LinkedBlockingQueue<ByteArray>()
        val failures=CopyOnWriteArrayList<Throwable>()
        val writes=AtomicInteger()
        val executor=Executors.newSingleThreadExecutor()
        val link=IapLink(input,object:OutputStream(){
            override fun write(b:Int)=write(byteArrayOf(b.toByte()),0,1)
            override fun write(b:ByteArray,off:Int,len:Int){
                if(writes.incrementAndGet()==failWrite)throw IOException("synthetic renegotiation write failure")
                packets.add(b.copyOfRange(off,off+len))
            }
        },{},{failures.add(it)},localMaxTxFrameBytes=localTx,localReceiveWindow=localWindow,
            localCumulativeAckTimeoutMs=localAck,localMaxCumulativeAck=localMaxAck,
            firstByteTimeoutMs=0,detectTimeoutMs=0)
        fun put(f:IapWire.Frame,version:Int=1){check(input.offer(IapWire.packet(f,version)))}
        fun bytes():ByteArray=checkNotNull(packets.poll(2,TimeUnit.SECONDS)){"no response"}
        fun read(version:Int=1)=IapWire.read(ByteArrayInputStream(bytes()),packetVersion=version)
        fun begin(payload:ByteArray,seq:Int=254):Pair<Future<*>,IapWire.Frame>{
            val start=executor.submit{link.start(allowDirectSyn=true,detectOnIdle=false)}
            put(IapWire.Frame(0x80,seq,0,0,payload))
            return start to read()
        }
        fun next(previous:IapWire.Frame,payload:ByteArray,seq:Int=(previous.ack+1) and 255):IapWire.Frame{
            put(IapWire.Frame(0xc0,seq,previous.seq,0,payload))
            return read().also{
                assertEquals(0xc0,it.flags);assertEquals((previous.seq+1) and 255,it.seq)
                assertEquals(seq,it.ack);assertPending()
            }
        }
        fun assertPending(){
            assertNull(link.negotiatedControlSession);assertNull(link.negotiatedSynchronizationSchema)
            assertEquals(0,link.generation)
        }
        fun confirm(start:Future<*>,reply:IapWire.Frame,version:Int=1){
            put(IapWire.Frame(0x40,reply.ack,reply.seq,0,byteArrayOf()),version)
            start.get(3,TimeUnit.SECONDS)
        }
        fun field(name:String):Any=checkNotNull(IapLink::class.java.getDeclaredField(name).also{it.isAccessible=true}.get(link))
        fun error(start:Future<*>):Throwable{
            val e=assertThrows(ExecutionException::class.java){start.get(3,TimeUnit.SECONDS)}
            assertPending();return checkNotNull(e.cause)
        }
        override fun close(){link.close();input.close();executor.shutdownNow()}
    }

    @Test(timeout=6000) fun acceptsOurCounterproposalWithoutPrematureCommit(){
        Peer().use{p->
            val (start,first)=p.begin(body(extra=byteArrayOf(11,2,1)))
            assertEquals(1000,IapWire.u16(first.payload,4));assertEquals(10,IapWire.u16(first.payload,6))
            assertEquals(0,first.payload[9].toInt());assertEquals(16,first.payload.size)
            assertArrayEquals(byteArrayOf(10,0,2,11,2,1),first.payload.copyOfRange(10,16))
            val epoch=p.link.linkEpoch
            // Keep the PEER's window/MTU, adopt our negotiated retry/ACK/session values.
            val second=p.next(first,body(retry=1000,ack=10,maxAck=0))
            assertFalse(start.isDone);assertEquals(epoch,p.link.linkEpoch)
            assertThrows(IllegalStateException::class.java){p.link.send(0xaa00)}
            p.confirm(start,second)
            assertEquals(1,p.link.generation);assertEquals(IapLink.ControlSession(10,0,2),p.link.negotiatedControlSession)
        }
    }

    @Test(timeout=6000) fun successiveRoundsMergeCurrentWorkAndLatestPeerLimits(){
        Peer().use{p->
            val (start,first)=p.begin(body(retry=200,mtu=1024))
            val second=p.next(first,body(retry=50,retries=2,window=7,mtu=4096,id=7,controlVersion=1))
            val third=p.next(second,body(retry=500,retries=20,window=3,mtu=3000,id=8,controlVersion=2))
            assertEquals(50,IapWire.u16(third.payload,4));assertEquals(2,third.payload[8].toInt())
            assertEquals(1,third.payload[1].toInt());assertEquals(65535,IapWire.u16(third.payload,2))
            assertEquals(1000L,p.field("retryMs")) // active defaults, not pending50
            p.confirm(start,third)
            assertEquals(50L,p.field("retryMs"));assertEquals(2,p.field("retryCount"))
            assertEquals(3,p.field("peerWindow"));assertEquals(3000,p.field("maxPacket"))
            assertEquals(IapLink.ControlSession(8,0,2),p.link.negotiatedControlSession)
            assertEquals(IapStartupPolicy.Schema.CSM_CONTROL_V2,
                IapStartupPolicy.selectSchema(IapControlSessionKind.CARPLAY,p.link.negotiatedControlSession!!.version))
            p.put(IapWire.Frame(0x40,(third.ack+1) and 255,third.seq,8,IapWire.csm(0x5703)))
            assertEquals((third.ack+1) and 255,p.read().ack);assertEquals(0x5703,p.link.poll(1000)?.id)
        }
    }

    @Test(timeout=6000) fun versionsNegotiateDownButFramingChangesOnlyAfterConfirmation(){
        for((initial,next,expected) in listOf(Triple(3,2,2),Triple(2,3,2),Triple(2,1,1)))Peer().use{p->
            val (start,first)=p.begin(body(link=initial))
            val second=p.next(first,body(link=next,id=7,controlVersion=1))
            assertEquals(expected,second.payload[0].toInt());assertEquals(1,p.field("packetVersion"))
            p.confirm(start,second)
            assertEquals(expected,p.field("linkVersion"))
            p.put(IapWire.Frame(0x40,0,second.seq,7,IapWire.csm(0x5703)),expected)
            assertEquals(0,p.read(expected).ack);assertEquals(0x5703,p.link.poll(1000)?.id)
        }
    }

    @Test(timeout=6000) fun extensionsAcceptAndPeerSessionTableIsPreservedDuringRenegotiation(){
        Peer().use{p->
            val (start,first)=p.begin(body(link=3))
            val second=p.next(first,body(link=3,extensions=byteArrayOf(0x54,0x55),extra=byteArrayOf(11,2,1)))
            assertEquals(0,second.payload[10].toInt());assertEquals(2,second.payload[11].toInt())
            assertArrayEquals(byteArrayOf(10,0,2,11,2,1),second.payload.copyOfRange(12,18))
            val third=p.next(second,body(link=3,extensions=byteArrayOf()))
            p.confirm(start,third);assertEquals(1,p.link.generation)
            assertEquals(IapLink.ControlSession(10,0,2),p.link.negotiatedControlSession)
        }
    }

    @Test(timeout=6000) fun identicalSynAckRetransmissionReusesCurrentReplyBeforeAckAssociation(){
        Peer().use{p->
            val (start,first)=p.begin(body())
            val proposal=IapWire.Frame(0xc0,255,first.seq,0,body(retry=1000,ack=10,maxAck=0))
            p.put(proposal);val second=p.read()
            p.put(proposal) // acknowledges first, not second: an exact retransmission
            assertArrayEquals(IapWire.packet(second),p.bytes());p.assertPending()
            p.confirm(start,second)
        }
    }

    @Test(timeout=8000) fun replacementTimerUsesActiveDefaultsAndOnlyLatestProposal(){
        Peer().use{p->
            val (start,first)=p.begin(body())
            val second=p.next(first,body(retry=20,retries=1))
            assertNull(p.packets.poll(150,TimeUnit.MILLISECONDS))
            assertArrayEquals(IapWire.packet(second),p.bytes());p.assertPending()
            p.confirm(start,second)
            assertNull(p.packets.poll(1200,TimeUnit.MILLISECONDS))
        }
    }

    @Test(timeout=6000) fun oldOrFutureAckCannotCommitOrChangeTheNextRound(){
        for(synAck in listOf(false,true))Peer().use{p->
            val (start,first)=p.begin(body())
            val second=p.next(first,body(retry=50))
            p.put(IapWire.Frame(if(synAck)0xc0 else 0x40,if(synAck)0 else second.ack,first.seq,0,
                if(synAck)body(retry=20) else byteArrayOf()))
            assertEquals("Unassociated SYN ACK",p.error(start).message)
        }
    }

    @Test(timeout=6000) fun freshSynRestoresLocalOfferButDoesNotRefillProposalBudget(){
        Peer().use{p->
            val (start,first)=p.begin(body())
            val second=p.next(first,body(retry=20,retries=1))
            val epoch=p.link.linkEpoch
            p.put(IapWire.Frame(0x80,0,0,0,body(retry=2000,id=7)))
            val reset=p.read();assertEquals((second.seq+1) and 255,reset.seq)
            assertEquals(1000,IapWire.u16(reset.payload,4));assertEquals(30,reset.payload[8].toInt())
            assertTrue(p.link.linkEpoch>epoch);p.confirm(start,reset)
        }
    }

    @Test(timeout=6000) fun repeatedSynAckProposalsExhaustBudgetInsteadOfPublishing(){
        Peer().use{p->
            val (start,first)=p.begin(body());var reply=first
            repeat(10){
                p.put(IapWire.Frame(0xc0,(reply.ack+1) and 255,reply.seq,0,body()))
                reply=p.read();assertEquals(0xc0,reply.flags)
            }
            assertEquals("iAP SYN proposal budget exhausted; link dead",p.error(start).message)
            assertEquals(11,p.writes.get())
        }
    }

    @Test(timeout=6000) fun newSynAckDoesNotRefillTheRetransmissionBudget(){
        Peer().use{p->
            val firstSyn=IapWire.Frame(0x80,254,0,0,body())
            val (start,first)=p.begin(firstSyn.payload)
            repeat(30){p.put(firstSyn);assertArrayEquals(IapWire.packet(first),p.bytes())}
            val proposal=IapWire.Frame(0xc0,255,first.seq,0,body(retry=50))
            p.put(proposal);val second=p.read();assertEquals(0xc0,second.flags)
            p.put(proposal);assertTrue(p.error(start) is java.net.SocketTimeoutException)
        }
    }

    @Test(timeout=6000) fun unsupportedOrMalformedSecondProposalDoesNotReceiveAReply(){
        val cases=listOf(body(controlVersion=3),body(retry=1),body(extensions=byteArrayOf(0x54),link=3),
            body(extra=byteArrayOf(10,2,1)),body().dropLast(1).toByteArray())
        for(payload in cases)Peer().use{p->
            val (start,first)=p.begin(body())
            p.put(IapWire.Frame(0xc0,255,first.seq,0,payload));p.error(start)
            assertEquals(1,p.writes.get())
        }
    }

    @Test(timeout=6000) fun reliabilityModeSwitchRemainsAnExplicitUnsupportedBoundary(){
        for(zero in listOf(false,true))Peer().use{p->
            val (start,first)=p.begin(body(zero=zero))
            p.put(IapWire.Frame(0xc0,255,first.seq,0,body(zero=!zero)))
            assertEquals("SYNC_MODE_CHANGE_UNSUPPORTED",p.error(start).message)
            assertEquals(1,p.writes.get())
        }
    }

    @Test(timeout=6000) fun unchangedNoRetransmitStillWaitsForFinalConfirmation(){
        Peer().use{p->
            val (start,first)=p.begin(body(zero=true))
            val second=p.next(first,body(zero=true,id=7))
            p.confirm(start,second);p.link.send(0xaa00);assertEquals(7,p.read().session)
        }
    }

    @Test(timeout=6000) fun changedPayloadAtSamePsnIsNotMistakenForAnExactRetransmission(){
        Peer().use{p->
            val (start,first)=p.begin(body())
            val second=p.next(first,body(retry=50))
            p.put(IapWire.Frame(0xc0,second.ack,second.seq,0,body(retry=20)))
            assertEquals("SYNC_SAME_PSN_CHANGED",p.error(start).message)
        }
    }

    @Test(timeout=8000) fun detectionCancelsRenegotiatedTimerAndRequiresNewEpochConfirmation(){
        Peer().use{p->
            val (start,first)=p.begin(body());p.next(first,body(retry=20))
            val epoch=p.link.linkEpoch
            check(p.input.offer(IapWire.detection));assertArrayEquals(IapWire.detection,p.bytes())
            assertTrue(p.link.linkEpoch>epoch);assertNull(p.packets.poll(1200,TimeUnit.MILLISECONDS))
            p.put(IapWire.Frame(0x80,9,0,0,body(id=7)))
            val fresh=p.read();p.confirm(start,fresh);assertEquals(7,p.link.negotiatedControlSession!!.id)
        }
    }

    @Test(timeout=6000) fun outputFailureWhileReplacingProposalNeverCommits(){
        Peer(failWrite=2).use{p->
            val (start,first)=p.begin(body())
            p.put(IapWire.Frame(0xc0,255,first.seq,0,body(retry=50)))
            assertEquals("synthetic renegotiation write failure",p.error(start).message)
            assertEquals(1,p.failures.size)
        }
    }

    @Test(timeout=6000) fun acknowledgementPolicyUsesTheCurrentOfferAndCommitsItsSchema(){
        Peer(localAck=500,localMaxAck=3,localWindow=5).use{p->
            val (start,first)=p.begin(body(ack=300,maxAck=2))
            assertEquals(300,IapWire.u16(first.payload,6));assertEquals(2,first.payload[9].toInt())
            val second=p.next(first,body(ack=100,maxAck=1))
            val third=p.next(second,body(ack=500,maxAck=3))
            assertEquals(100,IapWire.u16(third.payload,6));assertEquals(1,third.payload[9].toInt())
            p.confirm(start,third)
            assertEquals(100L,p.field("ackTimeoutMs"));assertEquals(1,p.field("maxCumulativeAck"))
            assertEquals(IapLink.SynchronizationSchema.LINK_SYNC_V1_RELIABLE,p.link.negotiatedSynchronizationSchema)
        }
    }

    @Test(timeout=6000) fun latestPeerMtuCannotExceedOurTransmitCapacity(){
        Peer(localTx=1024).use{p->
            val (start,first)=p.begin(body(mtu=2048))
            val second=p.next(first,body(mtu=65535))
            p.confirm(start,second);assertEquals(1024,p.field("maxPacket"))
        }
    }

    @Test(timeout=6000) fun dataOrWrongPeerPsnCannotStandInForPureConfirmation(){
        for(data in listOf(false,true))Peer().use{p->
            val (start,first)=p.begin(body());val second=p.next(first,body(retry=50))
            p.put(IapWire.Frame(0x40,(second.ack+1) and 255,second.seq,if(data)10 else 0,
                if(data)IapWire.csm(0x1d01) else byteArrayOf()))
            assertEquals("SYNC_CONFIRMATION_INVALID",p.error(start).message)
        }
    }

    @Test(timeout=8000) fun closingDuringRenegotiationCancelsTimerWithoutProtocolFailure(){
        Peer().use{p->
            val (start,first)=p.begin(body());p.next(first,body(retry=50))
            p.link.close();assertThrows(ExecutionException::class.java){start.get(3,TimeUnit.SECONDS)}
            p.assertPending();assertTrue(p.failures.isEmpty())
            assertNull(p.packets.poll(1200,TimeUnit.MILLISECONDS))
        }
    }

    @Test(timeout=8000) fun establishedVersionTwoRenegotiatesUsingOldFramingUntilCommit(){
        Peer().use{p->
            val (start,first)=p.begin(body(link=2));p.confirm(start,first)
            p.put(IapWire.Frame(0x80,10,0,0,body(link=3)),2)
            val reset=p.read(2);assertNull(p.link.negotiatedControlSession)
            p.put(IapWire.Frame(0xc0,11,reset.seq,0,body(link=1,id=7,controlVersion=1)),2)
            val next=p.read(2);assertEquals(1,next.payload[0].toInt())
            assertNull(p.link.negotiatedControlSession);assertEquals(1,p.link.generation)
            p.put(IapWire.Frame(0x40,11,next.seq,0,byteArrayOf()),2)
            // Next frame can immediately follow in the NEW framing, with no sleep/poll race.
            p.put(IapWire.Frame(0x40,12,next.seq,7,IapWire.csm(0x5703)))
            assertEquals(12,p.read().ack);assertEquals(0x5703,p.link.poll(1000)?.id)
            assertEquals(2,p.link.generation);assertEquals(IapLink.ControlSession(7,0,1),p.link.negotiatedControlSession)
        }
    }

    @Test(timeout=6000) fun newProposalWrapsLocalPsnWithoutReusingThePreviousConfirmation(){
        Peer().use{p->
            // Exercise the existing private recovery entry's explicit PSN parameter;
            // no production test hook and no mutation of a live pending frame.
            val budgetType=IapLink::class.java.declaredClasses.single{it.simpleName=="SynchronizationBudget"}
            val budget=budgetType.getDeclaredConstructor().also{it.isAccessible=true}.newInstance()
            val exchange=IapLink::class.java.declaredMethods.single{it.name=="exchangeSynchronization"}.also{it.isAccessible=true}
            val start=p.executor.submit{exchange.invoke(p.link,false,null,budget,255,false)}
            p.put(IapWire.Frame(0x80,254,0,0,body()))
            val first=p.read();assertEquals(255,first.seq)
            val second=p.next(first,body(retry=1000,ack=10,maxAck=0))
            assertEquals(0,second.seq);p.confirm(start,second);assertEquals(1,p.link.generation)
        }
    }
}
