package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import io.github.iamr0s.carvex.carplay.core.CarPlayAudioPlan

/** Capability probe only. An advertised Android codec is not a negotiated CarPlay wire profile. */
object AudioCodecSupport {
    fun configuration(format:CarPlayAudioPlan.Format,bitrate:Int?=null):MediaFormat {
        val codec=format.codec
        require(codec!=CarPlayAudioPlan.Codec.PCM16){"PCM does not require a compressed encoder"}
        val mime=if(codec==CarPlayAudioPlan.Codec.OPUS)"audio/opus" else "audio/mp4a-latm"
        val request=MediaFormat.createAudioFormat(mime,format.rate,format.channels)
        when(codec){
            CarPlayAudioPlan.Codec.AAC_LC->request.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            CarPlayAudioPlan.Codec.AAC_ELD->request.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            else->Unit
        }
        bitrate?.let{require(it>0);request.setInteger(MediaFormat.KEY_BIT_RATE,it)}
        return request
    }
    fun encoders(format:CarPlayAudioPlan.Format,bitrate:Int?=null):List<String> {
        if(format.codec==CarPlayAudioPlan.Codec.PCM16)return emptyList()
        val request=configuration(format,bitrate)
        val mime=requireNotNull(request.getString(MediaFormat.KEY_MIME))
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter{info->
            info.isEncoder&&info.supportedTypes.any{it.equals(mime,true)}&&runCatching{
                info.getCapabilitiesForType(mime).isFormatSupported(request)
            }.getOrDefault(false)
        }.map{it.name}.distinct()
    }
    /** Decoder half of the local/receiver intersection for incoming microphone audio. */
    fun decoders(format:CarPlayAudioPlan.Format):List<String> {
        if(format.codec==CarPlayAudioPlan.Codec.PCM16)return listOf(format.codec.name)
        val request=configuration(format)
        val mime=requireNotNull(request.getString(MediaFormat.KEY_MIME))
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter{info->
            !info.isEncoder&&info.supportedTypes.any{it.equals(mime,true)}&&runCatching{
                info.getCapabilitiesForType(mime).isFormatSupported(request)
            }.getOrDefault(false)
        }.map{it.name}.distinct()
    }
}
