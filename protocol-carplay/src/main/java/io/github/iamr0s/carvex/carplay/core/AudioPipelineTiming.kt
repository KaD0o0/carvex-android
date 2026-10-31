package io.github.iamr0s.carvex.carplay.core

/** A clock at the PCM submission boundary, not an estimate of the host's capture latency.
 * Reserve time before queue admission so rejected/expired PCM leaves a hole in the sample clock.
 * Only a gap in source submissions can move this clock; a slow encoder/writer cannot move it. */
internal class AudioSourceClock(private val rate:Int,private val gapThresholdNanos:Long=100_000_000L) {
    data class Reservation(val firstFrame:Long,val gapFrames:Long)
    private var frames=0L
    private var originNanos:Long?=null
    private var initialFrameCount=0
    private var previousArrival:Long?=null
    init{require(rate>0&&gapThresholdNanos>=0)}

    @Synchronized fun reserve(frameCount:Int,arrivalNanos:Long):Reservation {
        require(frameCount>0)
        val arrival=maxOf(arrivalNanos,previousArrival?:arrivalNanos)
        if(originNanos==null){originNanos=arrival;initialFrameCount=frameCount}
        // Callbacks normally arrive when the current block has filled. Compare against the
        // whole reserved sample timeline, not just the preceding callback: repeated small
        // pauses/omitted upstream blocks must not accumulate into permanently stale output.
        // Bounded callback jitter does not accumulate because this baseline stays sample-based.
        val expectedFrames=Math.addExact(frames,frameCount.toLong())-initialFrameCount
        val elapsed=Math.addExact(Math.multiplyExact(expectedFrames/rate,1_000_000_000L),
            expectedFrames%rate*1_000_000_000L/rate)
        val expectedArrival=Math.addExact(requireNotNull(originNanos),elapsed)
        val gap=(arrival-expectedArrival).coerceAtLeast(0)
        val skipped=if(gap>gapThresholdNanos)Math.addExact(Math.multiplyExact(gap/1_000_000_000L,rate.toLong()),
            gap%1_000_000_000L*rate/1_000_000_000L) else 0L
        frames=Math.addExact(frames,skipped)
        val first=frames
        frames=Math.addExact(frames,frameCount.toLong())
        previousArrival=arrival
        return Reservation(first,skipped)
    }
    fun ptsUs(frame:Long):Long=Math.multiplyExact(frame,1_000_000L)/rate
    @Synchronized fun endPtsUs():Long=ptsUs(frames)
    @Synchronized fun sourceTimeNanos(ptsUs:Long):Long=
        Math.addExact(requireNotNull(originNanos),Math.multiplyExact(ptsUs,1000L))
}

/** Feed a dense sample clock to MediaCodec and retain source discontinuities separately.
 * Some platform encoders normalize output PTS to their own continuous sample count. Do not
 * rely on them to preserve a pause or a dropped input block. Segments are added only at a
 * source discontinuity and retired as output advances; this runs on the codec worker only. */
internal class AudioSampleTimeline(private val rate:Int) {
    private data class Segment(val denseStart:Long,val sourceStart:Long)
    private val segments=java.util.ArrayDeque<Segment>()
    private var denseFrames=0L
    private var previousSourceEnd:Long?=null
    private var lastOutputFrame:Long?=null
    val retainedSegments:Int get()=segments.size
    init{require(rate>0)}

    fun record(firstSourceFrame:Long,frameCount:Int):Long {
        require(firstSourceFrame>=0&&frameCount>0)
        require(previousSourceEnd?.let{firstSourceFrame>=it}!=false){"Source sample positions moved backwards"}
        if(previousSourceEnd!=firstSourceFrame)segments.addLast(Segment(denseFrames,firstSourceFrame))
        val pts=ptsUs(denseFrames)
        denseFrames=Math.addExact(denseFrames,frameCount.toLong())
        previousSourceEnd=Math.addExact(firstSourceFrame,frameCount.toLong())
        return pts
    }
    fun endPtsUs():Long=ptsUs(denseFrames)
    fun ptsUs(frame:Long):Long=Math.multiplyExact(frame,1_000_000L)/rate
    fun sourceFrame(codecPtsUs:Long):Long {
        check(segments.isNotEmpty()){"Codec output before PCM input"}
        val scaled=Math.multiplyExact(codecPtsUs,rate.toLong())
        val dense=if(scaled>=0)Math.addExact(scaled,500_000L)/1_000_000L
            else -Math.addExact(-scaled,500_000L)/1_000_000L
        require(lastOutputFrame?.let{dense>=it}!=false){"Encoder output PTS moved backwards"}
        while(segments.size>1){
            val next=segments.iterator().let{it.next();it.next()}
            if(next.denseStart>dense)break
            segments.removeFirst()
        }
        lastOutputFrame=dense
        val segment=segments.first
        // Negative priming PTS map before the first input, including when the first accepted
        // input was not source frame zero. An AU spanning a boundary uses its start sample;
        // the following AU observes the hole without inventing or splitting compressed data.
        return Math.addExact(segment.sourceStart,dense-segment.denseStart)
    }
}

/** Fixed output pacing after one codec-startup calibration. Never re-anchor on writer lateness.
 * The first encoded AU (which may have a negative priming PTS) calibrates the codec's normal
 * startup delay. Its production time is used, so waiting in the output queue is not calibrated in.
 * Source positions retain gaps and locally discarded access units. The RTP clock separately
 * decides whether those positions belong on the wire, based on the SETUP reply. */
internal class AudioSendClock(private val rate:Int,val maximumLatenessNanos:Long=100_000_000L) {
    data class Plan(val dueNanos:Long,val timestamp:Long,val discontinuity:Boolean)
    private var firstPts:Long?=null
    private var lastPts:Long?=null
    private var previousEnd:Long?=null
    var startupDelayNanos=0L;private set
    init{require(rate>0&&maximumLatenessNanos>=0)}

    fun plan(ptsUs:Long,sourceTimeNanos:Long,encodedAtNanos:Long,samples:Int):Plan {
        require(samples>0)
        require(lastPts?.let{ptsUs>=it}!=false){"Encoder output PTS moved backwards"}
        if(firstPts==null){
            firstPts=ptsUs
            startupDelayNanos=(encodedAtNanos-sourceTimeNanos).coerceAtLeast(0)
        }
        val relative=Math.subtractExact(ptsUs,requireNotNull(firstPts))
        // PTS is in whole microseconds; nearest-sample rounding avoids losing a sample on
        // every 1024-frame AAC packet at 48/44.1 kHz.
        val timestamp=Math.addExact(Math.multiplyExact(relative,rate.toLong()),500_000L)/1_000_000L
        val discontinuity=previousEnd?.let{timestamp!=it}?:false
        previousEnd=Math.addExact(timestamp,samples.toLong());lastPts=ptsUs
        return Plan(Math.addExact(sourceTimeNanos,startupDelayNanos),timestamp,discontinuity)
    }
    fun expired(plan:Plan,nowNanos:Long):Boolean=nowNanos-plan.dueNanos>maximumLatenessNanos
}

/** Backpressure is allowed only after the codec buffer has been copied and returned. */
internal suspend fun <T> copyReleaseAndPublish(copy:()->T?,release:()->Unit,publish:suspend (T)->Unit) {
    val value=try{copy()}finally{release()}
    if(value!=null)publish(value)
}
