package io.github.iamr0s.carvex.carplay.core
/** Validate the actual encoded packet rather than assuming input size dictates codec frame duration. */
object CompressedAudioContract {
    fun aacLcConfig(csd:ByteArray,format:CarPlayAudioPlan.Format) {
        require(format.codec==CarPlayAudioPlan.Codec.AAC_LC)
        AacConfiguration.validate(csd,format)
    }
    /** RFC6716 TOC duration. Includes multiple frames; receiver profile clock uses decoded sample rate. */
    fun opusSamples(packet:ByteArray,rate:Int):Int {
        require(packet.isNotEmpty()&&rate in setOf(8000,12000,16000,24000,48000))
        val toc=packet[0].toInt() and 255
        val frameSamples=when {
            toc and 128!=0->(rate shl (toc ushr 3 and 3))/400
            toc and 96==96->if(toc and 8!=0)rate/50 else rate/100
            else->when(toc ushr 3 and 3){0->rate/100;1->rate/50;2->rate/25;else->rate*3/50}
        }
        val count=when(toc and 3){0->1;1,2->2;else->{require(packet.size>=2);packet[1].toInt() and 63}}
        require(count>0&&frameSamples*count<=rate*120/1000){"Invalid Opus packet duration"}
        return frameSamples*count
    }
}
