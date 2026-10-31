package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat

import org.junit.Assert.*
import org.junit.Test

/** 麦克风候选必须与主音频共用同一套优先序。
 *
 *  回归背景：AirSession 原先给麦克风自带一份 `ELD -> 其它` 的排序，于是车机只广告
 *  AAC-LC / OPUS 时，麦克风被判成"无可用编码"，而同样的掩码在主音频路径本可选出一个格式。 */
class MicrophoneCodecPolicyTest {
    private fun capture(type:Int,purpose:String,input:Long)=
        AudioCapabilities.Entry(type,purpose,input,null)

    @Test fun microphoneFallsBackToAacLcWhenEldIsNotAdvertised(){
        // 车机采集只广告 AAC-LC 48k/2（0x800000）。旧实现只认 ELD|PCM16 -> 返回 null。
        val entries=listOf(capture(CarPlayAudioPlan.CAPTURE_STREAM_TYPE,CarPlayAudioPlan.CAPTURE_PURPOSE,0x800000L))
        val chosen=CarPlayAudioPlan.capture(entries,PcmFormat(48000,2))
        assertNotNull("AAC-LC 必须能被选为麦克风编码",chosen)
        assertEquals(CarPlayAudioPlan.Codec.AAC_LC,chosen!!.codec)
    }

    @Test fun microphoneFallsBackToOpusWhenThatIsAllThatIsOffered(){
        val entries=listOf(capture(CarPlayAudioPlan.CAPTURE_STREAM_TYPE,CarPlayAudioPlan.CAPTURE_PURPOSE,0x40000000L))
        assertEquals(CarPlayAudioPlan.Codec.OPUS,CarPlayAudioPlan.capture(entries,PcmFormat(48000,1))!!.codec)
    }

    @Test fun microphoneKeepsTheSamePriorityOrderAsMainAudio(){
        // ELD 与 LC 同时广告时取 ELD —— 与 wirelessCandidates 的结论一致。
        val entries=listOf(capture(CarPlayAudioPlan.CAPTURE_STREAM_TYPE,CarPlayAudioPlan.CAPTURE_PURPOSE,0x2000000L or 0x800000L))
        assertEquals(CarPlayAudioPlan.Codec.AAC_ELD,CarPlayAudioPlan.capture(entries,PcmFormat(48000,2))!!.codec)
    }

    @Test fun microphonePcmOnlyIsNotSilentlyChosenByTheStandardPolicy(){
        // PCM 走 pcmCompatibility 显式兼容路径，不进标准优先序：只广告 PCM 时标准路径返回 null。
        val entries=listOf(capture(CarPlayAudioPlan.CAPTURE_STREAM_TYPE,CarPlayAudioPlan.CAPTURE_PURPOSE,0x8000L))
        assertNull(CarPlayAudioPlan.capture(entries,PcmFormat(48000,2)))
    }

    @Test fun microphoneIgnoresOutputOnlyAndWrongPurposeEntries(){
        // 只有 outputMask（车机播放方向）不能被当成采集能力。
        val outputOnly=listOf(AudioCapabilities.Entry(CarPlayAudioPlan.CAPTURE_STREAM_TYPE,
            CarPlayAudioPlan.CAPTURE_PURPOSE,null,0x800000L))
        assertNull(CarPlayAudioPlan.capture(outputOnly,PcmFormat(48000,2)))
        // 方向对但用途不对（media 而非 speechRecognition）同样不算。
        val wrongPurpose=listOf(capture(CarPlayAudioPlan.CAPTURE_STREAM_TYPE,"media",0x800000L))
        assertNull(CarPlayAudioPlan.capture(wrongPurpose,PcmFormat(48000,2)))
    }
}
