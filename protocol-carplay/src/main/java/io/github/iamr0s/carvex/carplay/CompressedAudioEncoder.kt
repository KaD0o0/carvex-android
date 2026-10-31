package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import io.github.iamr0s.carvex.carplay.core.*
import io.github.iamr0s.carvex.pcm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.*

/** Local compressed encoding stage. Its output is NOT a CarPlay network packet.
 * Output may suspend for bounded backpressure, but never while a codec buffer is held. */
class CompressedAudioEncoder private constructor(
    private val codec:MediaCodec,val encoderName:String,val format:CarPlayAudioPlan.Format,
    private val output:suspend (Event)->Unit,private val failure:(Throwable)->Unit
):AutoCloseable {
    sealed interface Event {
        data class Configuration(val mime:String,val rate:Int,val channels:Int,val profile:Int?,
            val csd:Map<String,ByteArray>,val delaySamples:Int?,val paddingSamples:Int?):Event
        data class CodecConfig(val bytes:ByteArray):Event
        data class AccessUnit(val bytes:ByteArray,val presentationTimeUs:Long,val endOfStream:Boolean,
            val sourceTimeNanos:Long,val encodedAtNanos:Long,val codecPresentationTimeUs:Long):Event
    }
    data class Statistics(val submittedBlocks:Long,val droppedBlocks:Long,val accessUnits:Long,val encodedBytes:Long)
    private val input=Channel<AudioEncoderInput>(8)
    private val clock=AudioSourceClock(format.rate)
    private val closed=AtomicBoolean(false)
    private val finishing=AtomicBoolean(false)
    private val done=CompletableDeferred<Unit>()
    private val submitted=AtomicLong()
    private val dropped=AtomicLong()
    private val units=AtomicLong()
    private val bytesOut=AtomicLong()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    // Enter the try/finally before returning from construction; immediate close must still release codec.
    private val worker=scope.launch(start=CoroutineStart.UNDISPATCHED){runEncoder()}

    /** Nonblocking submission. A missing/slow consumer cannot accumulate unlimited source audio. */
    fun submit(pcm:ByteArray,source:PcmFormat):Boolean {
        // The host bridge already converted to the negotiated decoded format. Keeping that
        // contract here lets submission reserve exact sample positions even when a block drops.
        require(source==format.decodedPcm&&source.accepts(pcm.size))
        if(closed.get()||finishing.get())return false
        if(pcm.isEmpty())return true
        val atNs=System.nanoTime()
        val block=AudioEncoderInput.prepare(pcm,source,clock,atNs)
        if(input.trySend(block).isFailure){dropped.incrementAndGet();return false}
        submitted.incrementAndGet();return true
    }
    /** Drains delayed codec output and the final access unit. This does not send any network FLUSH. */
    suspend fun finish(){
        check(!closed.get()){"Encoder closed"}
        if(finishing.compareAndSet(false,true))input.send(AudioEncoderInput.endOfStream(System.nanoTime()))
        withTimeout(5000){done.await()}
    }
    fun statistics()=Statistics(submitted.get(),dropped.get(),units.get(),bytesOut.get())
    private suspend fun runEncoder(){
        val target=format.decodedPcm
        val timeline=AudioSampleTimeline(target.rate)
        val assembler=AudioEncoderOutput()
        val info=MediaCodec.BufferInfo()
        var pending=byteArrayOf();var offset=0
        var pendingFirstFrame=0L
        var eosRequested=false;var eosQueued=false;var eosReceived=false
        try{
            while(!eosReceived){
                currentCoroutineContext().ensureActive()
                var progress=false
                if(offset==pending.size&&!eosRequested){
                    val block=input.tryReceive().getOrNull()
                    if(block!=null){
                        progress=true
                        if(block.pcm==null)eosRequested=true
                        else{
                            val now=System.nanoTime()
                            if(block.expired(now)){dropped.incrementAndGet()}
                            else{
                                pending=block.pcm;offset=0;pendingFirstFrame=block.firstFrame
                            }
                        }
                    }
                }
                if(offset<pending.size||eosRequested&&!eosQueued){
                    val index=codec.dequeueInputBuffer(0)
                    if(index>=0){
                        val buffer=requireNotNull(codec.getInputBuffer(index));buffer.clear()
                        val frameBytes=target.channels*2
                        val count=minOf(pending.size-offset,buffer.remaining()/frameBytes*frameBytes)
                        if(offset<pending.size)check(count>0){"Encoder input cannot hold one PCM frame"}
                        if(count>0){
                            buffer.put(pending,offset,count)
                            val codecPts=timeline.record(pendingFirstFrame+offset/frameBytes,count/frameBytes)
                            codec.queueInputBuffer(index,0,count,codecPts,0);offset+=count
                        }else{
                            codec.queueInputBuffer(index,0,0,timeline.endPtsUs(),MediaCodec.BUFFER_FLAG_END_OF_STREAM);eosQueued=true
                        }
                        progress=true
                    }
                }
                while(true){
                    val index=codec.dequeueOutputBuffer(info,0)
                    if(index==MediaCodec.INFO_TRY_AGAIN_LATER)break
                    progress=true
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        val actual=codec.outputFormat
                        val rate=actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels=actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        check(rate==format.rate&&channels==format.channels){"Encoder changed requested audio format"}
                        val csd=buildMap<String,ByteArray>{
                            var i=0
                            while(actual.containsKey("csd-$i")){
                                actual.getByteBuffer("csd-$i")?.duplicate()?.let{buffer->put("csd-$i",ByteArray(buffer.remaining()).also{buffer.get(it)})};i++
                            }
                        }
                        fun integer(key:String)=if(actual.containsKey(key))actual.getInteger(key) else null
                        output(Event.Configuration(requireNotNull(actual.getString(MediaFormat.KEY_MIME)),rate,channels,
                            integer(MediaFormat.KEY_AAC_PROFILE),csd,integer(MediaFormat.KEY_ENCODER_DELAY),integer(MediaFormat.KEY_ENCODER_PADDING)))
                    }else if(index>=0){
                        copyReleaseAndPublish<Event>(copy={
                            val data=if(info.size==0)byteArrayOf() else requireNotNull(codec.getOutputBuffer(index)).duplicate().let{buffer->
                                buffer.position(info.offset);buffer.limit(info.offset+info.size);ByteArray(info.size).also{buffer.get(it)}
                            }
                            val config=info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0
                            val end=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                            val event=if(config){check(!end){"Config-only EOS is not an audio access unit"};data.takeIf{it.isNotEmpty()}?.let{Event.CodecConfig(it)}}
                            else assembler.append(data,info.presentationTimeUs,info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME!=0,end)?.let{unit->
                                if(unit.bytes.isNotEmpty()){units.incrementAndGet();bytesOut.addAndGet(unit.bytes.size.toLong())}
                                val sourcePts=if(unit.bytes.isEmpty())unit.presentationTimeUs else
                                    timeline.ptsUs(timeline.sourceFrame(unit.presentationTimeUs))
                                Event.AccessUnit(unit.bytes,sourcePts,unit.endOfStream,
                                    if(unit.bytes.isEmpty())0L else clock.sourceTimeNanos(sourcePts),System.nanoTime(),unit.presentationTimeUs)
                            }
                            if(end)eosReceived=true
                            event
                        },release={codec.releaseOutputBuffer(index,false)},publish=output)
                    }else break
                }
                if(!progress)delay(2)
            }
            done.complete(Unit)
        }catch(t:CancellationException){done.cancel(t);throw t}
        catch(t:Throwable){done.completeExceptionally(t);runCatching{failure(t)}}
        finally{
            closed.set(true);input.cancel();assembler.clear();pending.fill(0)
            runCatching{codec.stop()};runCatching{codec.release()};scope.cancel()
        }
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        input.cancel();worker.cancel();done.cancel()
    }
    companion object {
        /** Probe and configure real platform encoders; hardware failure may fall back to another advertised encoder. */
        suspend fun create(format:CarPlayAudioPlan.Format,bitrate:Int,output:suspend (Event)->Unit,failure:(Throwable)->Unit):CompressedAudioEncoder = withContext(Dispatchers.IO){
            require(format in CarPlayAudioPlan.formats&&format.codec!=CarPlayAudioPlan.Codec.PCM16)
            val candidates=AudioCodecSupport.encoders(format,bitrate)
            var last:Exception?=null
            for(name in candidates){
                currentCoroutineContext().ensureActive()
                val codec=try{MediaCodec.createByCodecName(name)}catch(t:Exception){last=t;continue}
                try{
                    val request=AudioCodecSupport.configuration(format,bitrate)
                    request.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
                    request.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,format.framesPerPacket*format.channels*2)
                    codec.configure(request,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
                    return@withContext CompressedAudioEncoder(codec,name,format,output,failure)
                }catch(t:Exception){runCatching{codec.release()};last=t}
            }
            throw UnsupportedOperationException("${"No configurable "}${format.codec}${" encoder for "}${format.rate}${"/"}${format.channels}",last)
        }
    }
}
