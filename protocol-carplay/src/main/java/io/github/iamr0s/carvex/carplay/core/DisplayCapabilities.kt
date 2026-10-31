package io.github.iamr0s.carvex.carplay.core
/** Receiver descriptions are candidates, not a device-specific type allowlist. */
object DisplayCapabilities {
    data class Selected(val index:Int,val type:Int?,val width:Int,val height:Int,val fps:Int,val uuid:String?,val reason:String)
    private fun integer(value:Any?):Int? {
        if(value !is Number)return null
        val n=value.toDouble()
        return if(n.isFinite()&&n>=Int.MIN_VALUE&&n<=Int.MAX_VALUE&&n==n.toInt().toDouble())n.toInt() else null
    }
    private fun numeric(value:Any?):String=when(value){
        null->"absent-or-null"
        is Number->value.toString()
        else->"invalid-"+value.javaClass.simpleName
    }
    fun summary(value:Any?):String {
        if(value !is List<*>)return "displays=invalid-or-missing"
        return "displays="+value.size+" "+value.take(16).mapIndexed{index,item->
            if(item !is Map<*,*>)"#$index invalid-entry" else
                "#$index type="+(if(item.containsKey("type"))numeric(item["type"])else"missing")+
                " width="+numeric(item["widthPixels"])+" height="+numeric(item["heightPixels"])+
                " fps="+numeric(item["maxFPS"])+" uuidPresent="+(item["uuid"] is String)
        }.joinToString("; ")
    }
    /** supportedFps returns a locally supported rate <= the advertised/local limit, or null. */
    fun select(value:Any?,supportedFps:(Int,Int,Int)->Int?={_,_,fps->fps},log:(String)->Unit={}):Selected {
        val diagnostic=summary(value)
        val list=value as? List<*>?:error("${"No display capabilities: "}${diagnostic}")
        require(list.isNotEmpty()&&list.size<=16){"${"Invalid display count: "}${diagnostic}"}
        val candidates=list.mapIndexedNotNull{index,item->
            fun reject(reason:String):Selected?{log("${"INFO display #"}${index}${" excluded: "}${reason}");return null}
            val d=item as? Map<*,*>?:return@mapIndexedNotNull reject("not a dictionary")
            val type=integer(d["type"])
            if(d.containsKey("type")&&type==null)return@mapIndexedNotNull reject("invalid type")
            val w=integer(d["widthPixels"]);val h=integer(d["heightPixels"])
            if(w==null||h==null||w<=0||h<=0)return@mapIndexedNotNull reject("invalid dimensions")
            val limit=if(!d.containsKey("maxFPS"))15 else integer(d["maxFPS"])
            if(limit==null||limit<=0)return@mapIndexedNotNull reject("invalid FPS")
            val uuid=d["uuid"] as? String
            if(d.containsKey("uuid")&&uuid.isNullOrBlank())return@mapIndexedNotNull reject("invalid UUID")
            if(list.size>1&&uuid==null)return@mapIndexedNotNull reject("multiple displays require an addressable UUID")
            val fps=supportedFps(w,h,limit)?:return@mapIndexedNotNull reject("AVC surface encoder cannot support advertised dimensions")
            require(fps in 1..limit){"Encoder returned invalid FPS"}
            Selected(index,type,w,h,fps,uuid,"")
        }
        check(candidates.isNotEmpty()){"${"No usable display: "}${diagnostic}"}
        val ids=candidates.mapNotNull{it.uuid}
        require(ids.distinct().size==ids.size){"${"Ambiguous duplicate display UUIDs: "}${diagnostic}"}
        // type=0 is a documented primary hint; other integral types remain eligible.
        val ranked=candidates.sortedWith(compareByDescending<Selected>{it.type==0}
            .thenByDescending{it.width.toLong()*it.height}.thenByDescending{it.fps}.thenBy{it.index})
        val selected=ranked.first()
        val reason=if(selected.type==0)"primary hint, then area/FPS/order" else "no usable primary hint; inferred by area/FPS/order"
        log("${"INFO selected display #"}${selected.index}${" type="}${selected.type}${" "}${selected.width}${"x"}${selected.height}${" fps="}${selected.fps}${" candidates="}${candidates.size}${"; "}${reason}")
        return selected.copy(reason=reason)
    }
}
