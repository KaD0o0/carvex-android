package io.github.iamr0s.carvex.carplay.core

/** Local congestion policy; these thresholds are not receiver/protocol capabilities. */
class VideoRatePolicy(initial:Int,private val minimum:Int=1_000_000,private val maximum:Int=12_000_000) {
    init {require(minimum>0&&maximum>=minimum)}
    private var ceiling=initial.coerceIn(minimum,maximum)
    private var current=ceiling
    private var congested=0
    private var clear=0
    private var windowAt:Long?=null
    private var windowAge=0L
    private var windowWrite=0L
    @Synchronized fun configure(value:Int):Int {
        require(value>0)
        val accepted=value.coerceIn(minimum,maximum)
        ceiling=accepted;current=accepted;congested=0;clear=0;windowAt=null;windowAge=0;windowWrite=0
        return accepted
    }
    @Synchronized fun sample(nowMs:Long,ageMs:Long,writeMs:Long):Int? {
        windowAge=maxOf(windowAge,ageMs);windowWrite=maxOf(windowWrite,writeMs)
        val at=windowAt
        if(at==null){windowAt=nowMs;return null}
        if(nowMs-at<1000)return null
        val busy=windowAge>150||windowWrite>80
        val healthy=windowAge<60&&windowWrite<40
        windowAt=nowMs;windowAge=0;windowWrite=0
        congested=if(busy)congested+1 else 0
        clear=if(healthy)clear+1 else 0
        val next=when {
            congested>=3->{congested=0;clear=0;maxOf(minimum,(current.toLong()*4/5).toInt())}
            clear>=15->{clear=0;minOf(ceiling.toLong(),current.toLong()+250_000).toInt()}
            else->current
        }
        if(next==current)return null
        current=next;return next
    }
}
