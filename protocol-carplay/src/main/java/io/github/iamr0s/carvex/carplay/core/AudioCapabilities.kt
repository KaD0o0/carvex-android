package io.github.iamr0s.carvex.carplay.core
import io.github.iamr0s.carvex.pcm.PcmFormat

/** Receiver /info announcements, not a network transport contract. Preserve stream/audio type. */
object AudioCapabilities {
    data class Entry(val streamType:Int,val audioType:String?,val inputMask:Long?,val outputMask:Long?)
    data class Candidate(val entry:Entry,val formatBit:Long,val pcm:PcmFormat)
    private val pcm16=mapOf(
        0x4L to PcmFormat(8000,1),0x8L to PcmFormat(8000,2),
        0x10L to PcmFormat(16000,1),0x20L to PcmFormat(16000,2),
        0x40L to PcmFormat(24000,1),0x80L to PcmFormat(24000,2),
        0x100L to PcmFormat(32000,1),0x200L to PcmFormat(32000,2),
        0x400L to PcmFormat(44100,1),0x800L to PcmFormat(44100,2),
        0x4000L to PcmFormat(48000,1),0x8000L to PcmFormat(48000,2))
    private fun integer(value:Any?):Long? {
        if(value !is Number)return null
        if(value is Double||value is Float){
            val d=value.toDouble();if(!d.isFinite()||d<0||d>=Long.MAX_VALUE.toDouble())return null
            val n=d.toLong();return n.takeIf{it.toDouble()==d}
        }
        return value.toLong().takeIf{it>=0}
    }
    fun parse(value:Any?,log:(String)->Unit={}):List<Entry> {
        if(value==null){log("INFO audioFormats absent");return emptyList()}
        val items=value as? List<*>?:run{log("INFO audioFormats invalid container");return emptyList()}
        if(items.size>256){log("INFO audioFormats exceeds local parser budget 256");return emptyList()}
        return items.mapIndexedNotNull{index,item->
            val map=item as? Map<*,*>?:return@mapIndexedNotNull null
            val type=integer(map["type"])?.takeIf{it<=Int.MAX_VALUE}?.toInt()?:return@mapIndexedNotNull null
            val purpose=map["audioType"]
            val audioType=when(purpose){null->null;is String->purpose.takeIf{it.length<=128};is Number->integer(purpose)?.toString();else->null}
            val input=integer(map["audioInputFormats"]);val output=integer(map["audioOutputFormats"])
            log("${"INFO audio #"}${index}${" streamType="}${type}${" audioType="}"+(audioType?:"unknown")+" inputMask="+(input?.toString(16)?:"unknown")+" outputMask="+(output?.toString(16)?:"unknown"))
            Entry(type,audioType,input,output)
        }
    }
    /** Output is receiver playback, input is receiver capture per supplied guide; no stream-type guessing. */
    fun playback(entries:List<Entry>,format:PcmFormat):List<Candidate> = entries.flatMap{entry->
        pcm16.filter{(bit,pcm)->pcm==format&&entry.outputMask?.let{it and bit!=0L}==true}
            .map{(bit,pcm)->Candidate(entry,bit,pcm)}
    }
    fun capture(entries:List<Entry>):List<Candidate> = entries.flatMap{entry->
        pcm16.filter{(bit,_)->entry.inputMask?.let{it and bit!=0L}==true}
            .map{(bit,pcm)->Candidate(entry,bit,pcm)}
    }
}
