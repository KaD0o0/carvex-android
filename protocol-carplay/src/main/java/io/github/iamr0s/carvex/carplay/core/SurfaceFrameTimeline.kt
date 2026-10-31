package io.github.iamr0s.carvex.carplay.core

/** Align codec PTS to a monotonic clock once; preserve subsequent source-frame spacing. */
class SurfaceFrameTimeline {
    private var firstRawUs:Long?=null
    private var firstArrivalUs=0L
    private var previousUs:Long?=null
    fun map(rawUs:Long,arrivalUs:Long):Long {
        val first=firstRawUs
        if(first==null){firstRawUs=rawUs;firstArrivalUs=arrivalUs}
        val value=firstArrivalUs+(rawUs-(first?:rawUs))
        val previous=previousUs
        val mapped=if(previous==null)value else maxOf(value,previous+1)
        previousUs=mapped
        return mapped
    }
}
