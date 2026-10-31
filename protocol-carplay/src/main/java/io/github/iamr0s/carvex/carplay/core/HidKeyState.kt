package io.github.iamr0s.carvex.carplay.core

/** State reflects each delivered transition, including a partially accepted multi-key report. */
class HidKeyState {
    private val pressed=mutableMapOf<Pair<String?,String>,MutableSet<Int>>()
    @Synchronized fun dispatch(uuid:String?,prefix:String,usages:Set<Int>,callback:(String,Int,Boolean)->Boolean):Boolean {
        val active=pressed.getOrPut(uuid to prefix){linkedSetOf()}
        val changes=(active+usages).toList()
        var acceptedAll=true
        for(usage in changes){
            val down=usage in usages
            if((usage in active)==down)continue
            val accepted=callback(prefix,usage,down)
            if(accepted){if(down)active.add(usage)else active.remove(usage)}
            acceptedAll=accepted&&acceptedAll
        }
        return acceptedAll
    }
}
