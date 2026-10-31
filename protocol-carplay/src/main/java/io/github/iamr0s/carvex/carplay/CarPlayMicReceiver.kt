package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import io.github.iamr0s.carvex.carplay.core.*
import io.github.iamr0s.carvex.pcm.*
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated, ordered HU->phone MainAudio. Codec and key cleanup happen on the receiver worker. */
class CarPlayMicReceiver private constructor(private val socket:DatagramSocket,private val peer:InetAddress,
    private val peerPort:Int,private val key:ByteArray,private val alternateKey:ByteArray?,private val format:CarPlayAudioPlan.Format,
    private val codec:MediaCodec?,controlPort:Int?,private val output:suspend (ByteArray,PcmFormat)->Boolean,
    private val log:(String)->Unit,private val stopped:()->Unit):MicrophoneInput {
    private val closed=AtomicBoolean(false)
    @Volatile private var controlPort:Int?=controlPort
    /** The local sink exists before SETUP; the remote control port arrives in its response. */
    fun updateControlPort(value:Int?){require(value==null||value in 1..65535);controlPort=value}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override val ready:Boolean get()=!closed.get()&&!socket.isClosed
    val pcmFormat get()=format.decodedPcm
    init{scope.launch(start=CoroutineStart.UNDISPATCHED){receive()}}
    private suspend fun receive(){
        val buffer=ByteArray(2048)
        val auth=AudioRtpInput(key,100,alternateKey,log)
        val jitter=AudioInputJitter()
        val clock=RtpInputClock(format.rate)
        val info=MediaCodec.BufferInfo()
        var actual=format.decodedPcm;var converter=Pcm16Converter(actual,pcmFormat)
        var requested:Int?=null;var invalid=0L;var received=0L;var delivered=0L;var lastLogged=0L
        suspend fun drain(){
            val c=codec?:return
            while(true){
                val index=c.dequeueOutputBuffer(info,0)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    val f=c.outputFormat
                    val encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(encoding==AudioFormat.ENCODING_PCM_16BIT){"Microphone decoder output is not PCM16"}
                    actual=PcmFormat(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    converter=Pcm16Converter(actual,pcmFormat)
                }else if(index>=0){
                    try{
                        if(info.size>0){
                            val data=requireNotNull(c.getOutputBuffer(index)).duplicate()
                            data.position(info.offset);data.limit(info.offset+info.size)
                            val pcm=converter.convert(ByteArray(info.size).also{data.get(it)})
                            if(pcm.isNotEmpty()&&output(pcm,pcmFormat))delivered+=pcm.size
                        }
                    }finally{c.releaseOutputBuffer(index,false)}
                }else if(index==MediaCodec.INFO_TRY_AGAIN_LATER)return
            }
        }
        suspend fun decode(frame:AudioRtpInput.Frame){
            if(codec==null){
                val pcm=MediaAudioPackets.networkPcm(frame.payload)
                require(pcmFormat.accepts(pcm.size))
                if(output(pcm,pcmFormat))delivered+=pcm.size
            }else{
                var index=codec.dequeueInputBuffer(0)
                val deadline=System.nanoTime()+20_000_000L
                while(index<0&&System.nanoTime()<deadline){drain();delay(1);index=codec.dequeueInputBuffer(0)}
                if(index<0){log("Microphone decoder input timeout; packet dropped");return}
                val data=requireNotNull(codec.getInputBuffer(index));data.clear()
                require(data.remaining()>=frame.payload.size){"Microphone packet exceeds decoder input capacity"}
                data.put(frame.payload)
                codec.queueInputBuffer(index,0,frame.payload.size,clock.timeUs(frame.timestamp),0)
                drain()
            }
        }
        try{
            // Ensure try/finally is entered before construction returns, without blocking its caller.
            yield()
            socket.soTimeout=20
            while(currentCoroutineContext().isActive&&ready){
                val packet=DatagramPacket(buffer,buffer.size)
                try{
                    socket.receive(packet)
                    // 固件只在长度小于 0x5c9（最多 1480 字节）时处理；更大的不是合法麦克风包。
                    if(packet.length>=0x5c9){invalid++;continue}
                    if(packet.address==peer&&(peerPort==0||packet.port==peerPort||packet.port==controlPort)){
                        // Strip the 4-byte RTP header in the same copy: this runs for every
                        // received packet, and copying twice per packet is pure waste.
                        val end=packet.offset+packet.length
                        val skip=if(packet.length>=4&&(packet.data[packet.offset+1].toInt() and 255)==214)4 else 0
                        val wire=packet.data.copyOfRange(packet.offset+skip,end)
                        try{
                            auth.open(wire)?.let{frame->jitter.offer(frame,System.nanoTime());received++}
                        }catch(t:Exception){
                            invalid++
                            if(invalid==1L){
                                // 首个失败包留完整前缀与尾部：区分“头布局不同”“记录模式无尾部 nonce”“密钥方向不同”。
                                val sample=wire.copyOfRange(0,minOf(wire.size,64)).joinToString(""){"%02x".format(it)}
                                val tail=wire.takeLast(16).joinToString(""){"%02x".format(it)}
                                log("Microphone reject sample len="+wire.size+" pt="+(wire.getOrNull(1)?.toInt()?.and(255)?:-1)+
                                    " head="+sample+" tail16="+tail+" error="+t.javaClass.simpleName)
                            }
                            if(invalid==1L||invalid%100==0L)log("${"Microphone packet rejected: "}${t.javaClass.simpleName}${"; count="}${invalid}")
                        }
                    }
                }catch(_:SocketTimeoutException){}
                val missing=jitter.missing()
                val retransmitPort=controlPort
                if(missing!=null&&missing.first!=requested&&retransmitPort!=null&&missing.second in 1..64){
                    val request=AudioRetransmitProtocol.request(missing.first,missing.second)
                    socket.send(DatagramPacket(request,request.size,peer,retransmitPort));requested=missing.first
                }
                while(true){val frame=jitter.poll(System.nanoTime())?:break;decode(frame)}
                if(jitter.missing()==null)requested=null
                drain()
                if(received!=lastLogged&&(received==1L||received>0&&received%100==0L)){lastLogged=received;log("${"Microphone authenticated RTP="}${received}${" deliveredPCM="}${delivered}${" skipped="}${jitter.skipped}${"; ASR acceptance pending"}")}
            }
        }catch(t:CancellationException){throw t}
        catch(t:Exception){if(!closed.get())log("${"Microphone receiver failed: "}${t.javaClass.simpleName}${" "}${t.message}")}
        finally{
            closed.set(true);socket.close()
            codec?.let{runCatching{it.stop()};runCatching{it.release()}}
            key.fill(0);runCatching{stopped()};scope.cancel()
        }
    }
    override fun close(){if(!closed.compareAndSet(false,true))return;socket.close();scope.cancel()}
    companion object {
        suspend fun create(socket:DatagramSocket,peer:InetAddress,peerPort:Int,key:ByteArray,alternateKey:ByteArray?=null,format:CarPlayAudioPlan.Format,
            controlPort:Int?,output:suspend (ByteArray,PcmFormat)->Boolean,log:(String)->Unit,stopped:()->Unit={}):CarPlayMicReceiver = withContext(Dispatchers.IO){
            var codec:MediaCodec?=null
            try{
                if(format.codec!=CarPlayAudioPlan.Codec.PCM16){
                    val mime=if(format.codec==CarPlayAudioPlan.Codec.OPUS)"audio/opus" else MediaFormat.MIMETYPE_AUDIO_AAC
                    val c=MediaCodec.createDecoderByType(mime);codec=c
                    val f=MediaFormat.createAudioFormat(mime,format.rate,format.channels)
                    f.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
                    if(format.codec==CarPlayAudioPlan.Codec.OPUS){
                        val header=ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(format.channels.toByte()).putShort(0).putInt(format.rate).putShort(0).put(0).array()
                        f.setByteBuffer("csd-0",ByteBuffer.wrap(header))
                        f.setByteBuffer("csd-1",ByteBuffer.wrap(ByteArray(8)))
                        f.setByteBuffer("csd-2",ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).array()))
                    }else f.setByteBuffer("csd-0",ByteBuffer.wrap(AacConfiguration.decoderConfig(format)))
                    c.configure(f,null,null,0);c.start()
                }
                CarPlayMicReceiver(socket,peer,peerPort,key,alternateKey,format,codec,controlPort,output,log,stopped)
            }catch(t:Throwable){codec?.let{runCatching{it.release()}};socket.close();key.fill(0);throw t}
        }
    }
}
