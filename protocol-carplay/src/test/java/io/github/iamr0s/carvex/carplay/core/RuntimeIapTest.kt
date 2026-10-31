package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.sessioncore.AcceptedIdentification
import io.github.iamr0s.carvex.sessioncore.IdentifyInfo
import io.github.iamr0s.carvex.sessioncore.MessageId
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

class RuntimeIapTest {
    private fun syn()=IapWire.packet(IapWire.Frame(0x80,30,0,0,
        byteArrayOf(1,5)+IapWire.be16(2048)+IapWire.be16(100)+IapWire.be16(73)+byteArrayOf(1,3,10,0,1)))
    @Test(timeout=5000) fun inputBackpressureWrapCopyAndClose(){
        val input=BoundedByteInput(5)
        val first=byteArrayOf(1,2,3,4)
        assertTrue(input.offer(first));first.fill(9)
        assertEquals(1,input.read());assertEquals(2,input.read())
        assertFalse(input.offer(byteArrayOf(5,6,7,8)))
        assertTrue(input.offer(byteArrayOf(5,6,7)))
        assertArrayEquals(byteArrayOf(3,4,5,6,7),IapWire.exact(input,5))
        assertEquals(0,input.read(ByteArray(0),0,0))
        val executor=Executors.newSingleThreadExecutor()
        val waiting=CountDownLatch(1)
        try{
            val result=executor.submit<Int>{waiting.countDown();input.read()}
            assertTrue(waiting.await(1,TimeUnit.SECONDS));input.close()
            assertEquals(-1,result.get(1,TimeUnit.SECONDS).toInt())
            assertFalse(input.offer(byteArrayOf(8)))
        }finally{input.close();executor.shutdownNow()}
    }
    @Test(timeout=7000) fun runtimeOnlyStartsOnDataAndSyncsAcrossEveryByte(){
        for(detection in listOf(true,false)){
            val carInput=BoundedByteInput()
            val ready=CountDownLatch(1);val message=CountDownLatch(1)
            val states=CopyOnWriteArrayList<RuntimeIapState>()
            val commands=CopyOnWriteArrayList<Int>()
            val runtime=RuntimeIap({carInput.offer(it)},{},{states.add(it);if(it==RuntimeIapState.LINK_READY)ready.countDown()},
                {command->commands.add(command.id);assertArrayEquals(byteArrayOf(4,5),checkNotNull(command.fields)[1]);message.countDown()})
            try{
                assertTrue(states.isEmpty()) // No guessed readiness before actual data.
                val data=(if(detection)IapWire.detection else byteArrayOf())+syn()
                for(b in data)assertTrue(runtime.accept(byteArrayOf(b)))
                if(detection)assertArrayEquals(IapWire.detection,IapWire.exact(carInput,6))
                val response=IapWire.read(carInput)
                assertEquals(0xc0,response.flags);assertEquals(30,response.ack)
                val ack=IapWire.packet(IapWire.Frame(0x40,30,response.seq,0,byteArrayOf()))
                for(b in ack)assertTrue(runtime.accept(byteArrayOf(b)))
                assertTrue(ready.await(1,TimeUnit.SECONDS))
                val frame=IapWire.packet(IapWire.Frame(0x40,31,response.seq,10,IapWire.csm(0x5703,listOf(1 to byteArrayOf(4,5)))))
                for(b in frame)assertTrue(runtime.accept(byteArrayOf(b)))
                assertEquals(31,IapWire.read(carInput).ack)
                assertTrue(message.await(1,TimeUnit.SECONDS));assertEquals(listOf(0x5703),commands.toList())
                assertFalse(states.contains(RuntimeIapState.DEGRADED))
            }finally{runtime.close();carInput.close()}
            assertEquals(RuntimeIapState.CLOSED,states.last())
            assertFalse(runtime.accept(byteArrayOf(1)))
        }
    }
    @Test(timeout=4000) fun badRuntimeFrameDegradesAndRejectsFurtherData(){
        val degraded=CountDownLatch(1)
        val runtime=RuntimeIap({true},{},{if(it==RuntimeIapState.DEGRADED)degraded.countDown()})
        try{
            // Valid wire checksum, but invalid SYN schema: terminal rather than resynchronizable.
            val broken=IapWire.packet(IapWire.Frame(0x80,30,0,0,byteArrayOf(1)))
            runtime.accept(broken)
            assertTrue(degraded.await(1,TimeUnit.SECONDS))
            assertFalse(runtime.accept(syn()))
        }finally{runtime.close()}
    }
    @Test(timeout=7000) fun controlEventReplyPrecedesQueuedRuntimeRequest(){
        val phoneInput=BoundedByteInput();val carInput=BoundedByteInput()
        fun output(input:BoundedByteInput)=object:OutputStream(){
            override fun write(b:Int){check(input.offer(byteArrayOf(b.toByte())))}
            override fun write(b:ByteArray,off:Int,len:Int){if(len>0)check(input.offer(b.copyOfRange(off,off+len)))}
        }
        val outbound=LinkedBlockingQueue<ByteArray>(4)
        val runtime=RuntimeIap({outbound.offer(it)},{},{})
        val client=RtspClient(phoneInput,output(carInput)){r->
            val p=Plist.dictionary(r.body);val params=p["params"] as Map<*,*>
            (if(runtime.accept(params["data"] as ByteArray))200 else 501) to byteArrayOf()
        }
        val executor=Executors.newFixedThreadPool(2)
        try{
            val receiver=executor.submit{
                val first=RtspWire.read(carInput)
                RtspWire.write(output(phoneInput),"POST /command RTSP/1.0",mapOf("CSeq" to "77"),
                    Plist.encode(mapOf("type" to "iAPSendMessage","params" to mapOf("data" to IapWire.detection))))
                val eventReply=RtspWire.read(carInput)
                assertEquals(200,eventReply.status);assertEquals("77",eventReply.headers["cseq"])
                RtspWire.write(output(phoneInput),"RTSP/1.0 200 OK",mapOf("CSeq" to first.headers.getValue("cseq")))
                val next=RtspWire.read(carInput)
                assertEquals("2",next.headers["cseq"])
                val params=Plist.dictionary(next.body)["params"] as Map<*,*>
                assertArrayEquals(IapWire.detection,params["data"] as ByteArray)
                RtspWire.write(output(phoneInput),"RTSP/1.0 200 OK",mapOf("CSeq" to "2"))
            }
            val writer=executor.submit{
                val bytes=outbound.poll(2,TimeUnit.SECONDS)?:error("Runtime output missing")
                client.plist("POST","/command",mapOf("type" to "iAPSendMessage","params" to mapOf("data" to bytes)))
            }
            client.plist("POST","/feedback")
            receiver.get(3,TimeUnit.SECONDS);writer.get(3,TimeUnit.SECONDS)
        }finally{runtime.close();phoneInput.close();carInput.close();executor.shutdownNow()}
    }

    /** This fixture only fakes the AA/Identification result, never production authentication. */
    private fun syntheticIdentity() = IdentifyInfo(
        name="synthetic-name",modelIdentifier="synthetic-model",manufacturer="synthetic-maker",
        accessorySends=listOf(MessageId(0x57,3)),accessoryReceives=listOf(MessageId(0x57,2)),
    )

    private inner class IdentifiedRuntimePeer(
        private val negotiation:(IapLink)->AcceptedIdentification = {link->
            AcceptedIdentification(link.generation,link.linkEpoch,syntheticIdentity())
        },
    ):AutoCloseable {
        val states=CopyOnWriteArrayList<RuntimeIapState>()
        val logs=CopyOnWriteArrayList<String>()
        val messages=LinkedBlockingQueue<IapCommand>()
        val emitted=CopyOnWriteArrayList<IapWire.Frame>()
        val published=CopyOnWriteArrayList<AcceptedIdentification>()
        val publicationOrder=CopyOnWriteArrayList<String>()
        val invalidatedAtTerminalPublication=ConcurrentHashMap<RuntimeIapState,Boolean>()
        val session=AtomicReference<AcceptedIdentification>()
        val ready=CountDownLatch(1)
        val degraded=CountDownLatch(1)
        private val output=LinkedBlockingQueue<IapWire.Frame>()
        private var seq=30
        private var hostSeq=0
        val runtime=RuntimeIap({bytes->
            val frame=IapWire.read(ByteArrayInputStream(bytes))
            emitted.add(frame);output.offer(frame)
        },{logs.add(it)},{state->
            states.add(state)
            publicationOrder.add("state="+state)
            if(state==RuntimeIapState.DEGRADED||state==RuntimeIapState.CLOSED){
                session.get()?.let{accepted->
                    // Probe at publication time: asserting only after fail/close returns
                    // would also pass if invalidation incorrectly happened after notification.
                    // An unrelated command cannot mutate identity or emit an application reply.
                    val invalidated=try{
                        accepted.handle(IapCommand.Fields(0x5703,IapFields(emptyList())),
                            accepted.generation,accepted.epoch){}
                        false
                    }catch(_:IllegalStateException){true}
                    invalidatedAtTerminalPublication[state]=invalidated
                }
            }
            if(state==RuntimeIapState.CONTROL_READY)ready.countDown()
            if(state==RuntimeIapState.DEGRADED)degraded.countDown()
        },message={messages.offer(it)},negotiate={link->
            negotiation(link).also{session.set(it)}
        },identificationAccepted={accepted->
            published.add(accepted)
            publicationOrder.add("identity")
        })

        fun synchronize(){
            assertTrue(runtime.accept(syn()))
            val response=requireNotNull(output.poll(2,TimeUnit.SECONDS)){"SYN/ACK missing"}
            assertEquals(0xc0,response.flags);assertEquals(seq,response.ack)
            hostSeq=response.seq
            // The synthetic stale-result negotiation may fail immediately after this
            // ACK is queued; the return Boolean can already reflect that degradation.
            runtime.accept(IapWire.packet(IapWire.Frame(0x40,seq,hostSeq,0,byteArrayOf())))
        }
        fun startReady(){synchronize();assertTrue(logs.joinToString(" | "),ready.await(2,TimeUnit.SECONDS))}
        fun send(id:Int,vararg fields:Pair<Int,ByteArray>){
            seq=(seq+1) and 255
            assertTrue(runtime.accept(IapWire.packet(IapWire.Frame(0x40,seq,hostSeq,10,
                IapWire.csm(id,fields.toList())))))
        }
        /** FIFO business marker proves all previously queued messages were dispatched. */
        fun barrier(){
            send(0x5703,1 to byteArrayOf(7))
            val command=requireNotNull(messages.poll(2,TimeUnit.SECONDS)){"runtime business marker missing"}
            assertEquals(0x5703,command.id)
            assertArrayEquals(byteArrayOf(7),requireNotNull(command.fields)[1])
            assertTrue("Identification messages must not reach the business callback",messages.isEmpty())
        }
        fun assertNoApplicationReply(){
            assertTrue("Only SYN/ACK and link ACKs are permitted: "+emitted,
                emitted.all{it.flags and 0x80!=0||it.payload.isEmpty()})
        }
        override fun close(){runtime.close()}
    }

    private fun assertInvalidated(session:AcceptedIdentification){
        assertThrows(IllegalStateException::class.java){
            session.handle(IapCommand.Fields(0x1d06,IapFields(listOf(0 to IapWire.cstring("after close")))),
                session.generation,session.epoch){}
        }
    }

    @Test(timeout=7000) fun acceptedRuntimeHandlesIdentityDeltaWithoutRepliesOrCapabilityChanges(){
        IdentifiedRuntimePeer().use{peer->
            peer.startReady()
            val session=peer.session.get()
            val capabilities=session.capabilities
            assertEquals(listOf(session),peer.published.toList())
            assertTrue(peer.publicationOrder.indexOf("identity")<
                peer.publicationOrder.indexOf("state="+RuntimeIapState.CONTROL_READY))
            peer.send(0x1d06,0 to IapWire.cstring("renamed"),
                6 to byteArrayOf(0x43),7 to byteArrayOf(0x43,0))
            peer.barrier()
            assertEquals("renamed",session.info.name)
            assertEquals("synthetic-model",session.info.modelIdentifier)
            assertEquals("synthetic-maker",session.info.manufacturer)
            assertSame(capabilities,session.capabilities)
            assertEquals(setOf(MessageId(0x57,3)),session.capabilities.sentByAccessory)
            assertEquals(setOf(MessageId(0x57,2)),session.capabilities.receivedByAccessory)
            assertTrue(peer.logs.any{it.startsWith("INFO 1d06 update")})
            assertFalse(peer.states.contains(RuntimeIapState.DEGRADED))
            assertEquals("updates must not republish accepted negotiation",1,peer.published.size)
            peer.assertNoApplicationReply()
        }
    }

    @Test(timeout=7000) fun malformedRuntimeUpdateIsAtomicAndDoesNotInterruptOrdinaryMessages(){
        IdentifiedRuntimePeer().use{peer->
            peer.startReady()
            val session=peer.session.get()
            val before=session.info
            peer.send(0x1d06,0 to IapWire.cstring("must not commit"),2 to byteArrayOf(65,0,66))
            peer.barrier()
            assertSame(before,session.info)
            assertTrue(peer.logs.any{it.contains("1d06 identification update dropped parameter=2")})
            peer.send(0x1d06,0 to IapWire.cstring("valid later"))
            peer.barrier()
            assertEquals("valid later",session.info.name)
            assertFalse(peer.states.contains(RuntimeIapState.DEGRADED))
            peer.assertNoApplicationReply()
        }
    }

    @Test(timeout=7000) fun lateRuntimeCancelAndInitialInfoDoNotRestartOrDegradeAcceptedSession(){
        IdentifiedRuntimePeer().use{peer->
            peer.startReady()
            val session=peer.session.get()
            val before=session.info
            peer.send(0x1d05)
            peer.send(0x1d01,0 to IapWire.cstring("must not replace accepted identity"))
            peer.barrier()
            assertSame(before,session.info)
            assertEquals(1,peer.states.count{it==RuntimeIapState.CONTROL_READY})
            assertFalse(peer.states.contains(RuntimeIapState.DEGRADED))
            peer.assertNoApplicationReply()
        }
    }

    @Test(timeout=7000) fun runtimeCloseInvalidatesAcceptedIdentityEvenWithTheSameBinding(){
        IdentifiedRuntimePeer().use{peer->
            peer.startReady()
            val session=peer.session.get()
            val before=session.info
            peer.runtime.close()
            assertEquals(RuntimeIapState.CLOSED,peer.states.last())
            assertEquals(true,peer.invalidatedAtTerminalPublication[RuntimeIapState.CLOSED])
            assertInvalidated(session)
            assertSame(before,session.info)
            assertFalse(peer.runtime.accept(syn()))
            assertFalse(peer.runtime.send(0x5702,emptyList()))
        }
    }

    @Test(timeout=7000) fun runtimeFailureInvalidatesAcceptedIdentityBeforePublishingDegraded(){
        IdentifiedRuntimePeer().use{peer->
            peer.startReady()
            val session=peer.session.get()
            val before=session.info
            peer.runtime.fail(IOException("synthetic failure"))
            assertTrue(peer.degraded.await(2,TimeUnit.SECONDS))
            assertEquals(true,peer.invalidatedAtTerminalPublication[RuntimeIapState.DEGRADED])
            assertInvalidated(session)
            assertSame(before,session.info)
            assertFalse(peer.runtime.accept(syn()))
            assertFalse(peer.runtime.send(0x5702,emptyList()))
        }
    }

    @Test(timeout=10000) fun negotiationCannotPublishIdentityFromAnotherGenerationOrEpoch(){
        for(wrongGeneration in listOf(true,false)){
            IdentifiedRuntimePeer{link->AcceptedIdentification(
                link.generation+if(wrongGeneration)1 else 0,
                link.linkEpoch+if(wrongGeneration)0L else 1L,syntheticIdentity())}.use{peer->
                peer.synchronize()
                assertTrue(peer.logs.joinToString(" | "),peer.degraded.await(2,TimeUnit.SECONDS))
                assertFalse(peer.states.contains(RuntimeIapState.CONTROL_READY))
                assertInvalidated(peer.session.get())
                assertTrue("stale negotiation must not publish capabilities",peer.published.isEmpty())
                assertTrue(peer.messages.isEmpty())
                assertFalse(peer.runtime.send(0x5702,emptyList()))
                peer.assertNoApplicationReply()
            }
        }
    }

    @Test(timeout=12000) fun closeOrFailureWhileNegotiatingInvalidatesTheLateReturnedIdentity(){
        for(close in listOf(true,false)){
            val entered=CountDownLatch(1)
            val result=CompletableFuture<AcceptedIdentification>()
            val pending=AtomicReference<AcceptedIdentification>()
            IdentifiedRuntimePeer{link->
                pending.set(AcceptedIdentification(link.generation,link.linkEpoch,syntheticIdentity()))
                entered.countDown()
                // Model an external result that can arrive after cancellation: join ignores interrupt.
                result.join()
            }.use{peer->
                try{
                    peer.synchronize()
                    assertTrue(entered.await(2,TimeUnit.SECONDS))
                    if(close)peer.runtime.close() else peer.runtime.fail(IOException("synthetic negotiation failure"))
                    val session=pending.get()
                    result.complete(session)
                    // No production callback is exposed for the worker's failed commit. Probe only
                    // an unrelated command, which cannot mutate accepted identity or emit output.
                    val deadline=System.nanoTime()+2_000_000_000L
                    var invalidated=false
                    while(System.nanoTime()<deadline&&!invalidated){
                        invalidated=try{
                            session.handle(IapCommand.Fields(0x5703,IapFields(emptyList())),
                                session.generation,session.epoch){}
                            false
                        }catch(_:IllegalStateException){true}
                        if(!invalidated)Thread.sleep(5)
                    }
                    assertTrue("late negotiation result must be invalidated",invalidated)
                    assertInvalidated(session)
                    assertTrue("late negotiation must not publish capabilities",peer.published.isEmpty())
                    assertFalse(peer.states.contains(RuntimeIapState.CONTROL_READY))
                    assertEquals(if(close)RuntimeIapState.CLOSED else RuntimeIapState.DEGRADED,peer.states.last())
                    assertTrue(peer.messages.isEmpty())
                    assertFalse(peer.runtime.send(0x5702,emptyList()))
                }finally{
                    pending.get()?.let{result.complete(it)}
                    if(!result.isDone)result.completeExceptionally(IOException("test cleanup"))
                }
            }
        }
    }
}
