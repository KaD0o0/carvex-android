package io.github.iamr0s.carvex.carplay.core
/** Decides when a quiet surface-input encoder must have its last frame resubmitted.
 *
 * MediaCodec produces output only while buffers arrive on its input surface, so a picture that
 * stops changing turns into a media stream that stops as well: the control channel keeps its
 * OPTIONS heartbeat while the RTP flow goes silent, and a receiver then reports the video as lost.
 * Resubmitting the current picture is the only surface-input way to make the codec emit a packet
 * at all, and because the picture did not change the codec produces a tiny repeat P frame - never
 * an I frame - whose timestamp advances like any other frame's, since it travels through the codec
 * instead of being patched into the stream afterwards.
 *
 * The caller owns the cadence: it asks [beat] once per interval and repeats when told to, so a
 * still picture yields exactly one packet per interval. A repeat that comes back as an encoded
 * frame is recognised as the answer to the last request rather than as the picture moving again,
 * which is what keeps that cadence from collapsing into one packet every other interval; a picture
 * that really moves keeps the stream to itself again. */
class VideoIdleHeartbeat(private val intervalMs:Long=1_000,private val echoMs:Long=intervalMs/4) {
    init {
        require(intervalMs>0){"Heartbeat interval must be positive"}
        require(echoMs in 0 until intervalMs){"Echo window must be shorter than the interval"}
    }
    private var lastFrameMs:Long?=null
    private var lastBeatMs:Long?=null
    /** Records an encoded frame, whether it came from the picture source or from a repeat. */
    @Synchronized fun frame(nowMs:Long){
        val beat=lastBeatMs
        // A frame this close behind a repeat request is that repeat coming back, not the picture
        // moving, so it must not restart the quiet period.
        if(beat==null||nowMs-beat>echoMs)lastFrameMs=nowMs
    }
    /** Whether the still picture needs another repeat now. */
    @Synchronized fun beat(nowMs:Long):Boolean {
        // Nothing encoded yet means nothing to repeat, and a repeat cannot start the stream.
        val frame=lastFrameMs?:return false
        if(nowMs-frame<intervalMs)return false
        // The caller ticks at the interval, but a fixed-rate timer that compensates a late run can
        // fire a little early; refuse anything much sooner than a whole interval.
        val beat=lastBeatMs
        if(beat!=null&&nowMs-beat<intervalMs*3/4)return false
        lastBeatMs=nowMs
        return true
    }
}
