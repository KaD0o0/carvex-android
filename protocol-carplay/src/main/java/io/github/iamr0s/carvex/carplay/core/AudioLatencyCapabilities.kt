package io.github.iamr0s.carvex.carplay.core

/** Diagnostic /info declarations, not a negotiated end-to-end latency or a SETUP policy. */
object AudioLatencyCapabilities {
    data class Entry(
        val streamType:Int,
        val audioType:String?,
        val inputLatencyMicros:Long?,
        val outputLatencyMicros:Long?,
        /** Preserve exact qualifier keys; do not infer aliases or a wildcard matching rule. */
        val formatFields:Map<String,Long>
    )
    private val qualifierKeys=listOf("sampleRate","sr","ch","ss")
    private fun nonnegativeInteger(value:Any?):Long? = when(value){
        is Byte,is Short,is Int,is Long->(value as Number).toLong().takeIf{it>=0}
        is Float,is Double->{
            val number=(value as Number).toDouble()
            if(!number.isFinite()||number<0||number>=Long.MAX_VALUE.toDouble())null
            else number.toLong().takeIf{it.toDouble()==number}
        }
        else->null
    }
    fun parse(value:Any?,log:(String)->Unit={}):List<Entry> {
        if(value==null){log("INFO audioLatencies absent");return emptyList()}
        val items=value as? List<*>?:run{log("INFO audioLatencies invalid container");return emptyList()}
        if(items.size>256){log("INFO audioLatencies exceeds local parser budget 256");return emptyList()}
        return items.mapIndexedNotNull{index,item->
            val fields=item as? Map<*,*>?:return@mapIndexedNotNull null
            val type=nonnegativeInteger(fields["type"])?.takeIf{it<=Int.MAX_VALUE}?.toInt()
                ?:return@mapIndexedNotNull null
            val rawPurpose=fields["audioType"]
            val purpose=when(rawPurpose){
                null->null
                is String->rawPurpose.takeIf{it.length in 1..128&&it.none{ch->ch.isISOControl()}}
                    ?:return@mapIndexedNotNull null
                else->return@mapIndexedNotNull null
            }
            val qualifiers=buildMap {
                for(key in qualifierKeys)nonnegativeInteger(fields[key])?.let{put(key,it)}
            }
            val entry=Entry(type,purpose,nonnegativeInteger(fields["inputLatencyMicros"]),
                nonnegativeInteger(fields["outputLatencyMicros"]),qualifiers)
            val invalid=(listOf("inputLatencyMicros","outputLatencyMicros")+qualifierKeys)
                .filter{fields[it]!=null&&nonnegativeInteger(fields[it])==null}
            log("INFO audioLatency #"+index+" "+describe(entry)+
                if(invalid.isEmpty())"" else " invalidFields="+invalid.joinToString(","))
            entry
        }
    }
    /** Return every exact route match; different format qualifiers must never be silently merged. */
    fun matching(entries:List<Entry>,streamType:Int,audioType:String):List<Entry> =
        entries.filter{it.streamType==streamType&&it.audioType==audioType}

    fun summary(entries:List<Entry>,streamType:Int,audioType:String):String {
        val matches=matching(entries,streamType,audioType)
        return if(matches.isEmpty())"none" else matches.joinToString(" | ",transform=::describe)
    }
    private fun describe(entry:Entry):String =
        "type="+entry.streamType+" audioType="+(entry.audioType?:"unknown")+
            " inputLatencyMicros="+(entry.inputLatencyMicros?.toString()?:"unknown")+
            " outputLatencyMicros="+(entry.outputLatencyMicros?.toString()?:"unknown")+
            entry.formatFields.entries.joinToString(separator="",transform={" "+it.key+"="+it.value})
}
