package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*
import io.github.iamr0s.carvex.pcm.PcmFormat

/** One SETUP/stream identity and socket for MainAudio output and vehicle microphone input. */
class DuplexAudioTransport(private val output:NegotiatedAudioTransport,private val input:CarPlayMicReceiver):NegotiatedAudioTransport {
    override val ready get()=output.ready
    override val formats get()=output.formats
    override val microphoneFormat get()=input.pcmFormat
    override val microphoneReady get()=output.ready&&input.ready
    override val packetsSent get()=output.packetsSent
    override val lastPacketNanos get()=output.lastPacketNanos
    override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat)=output.send(category,pcm,format)
    override fun close(){input.close();output.close()}
}
