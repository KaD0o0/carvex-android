package io.github.iamr0s.carvex.carplay.core

/** Observes video work without ever taking the video serialization monitor or changing its deadlines.
 * A ticket starts before that monitor, so a blocked keepalive and the frame waiting behind it can
 * be distinguished. Stack sampling is bounded and runs only on the independent heartbeat caller. */
class VideoSendDiagnostics(
    private val log:(String)->Unit,
    private val nanoTime:()->Long=System::nanoTime,
    private val stackCapture:(Thread)->List<String> = {thread->thread.stackTrace.take(MAX_STACK_DEPTH).map{it.toString()}},
    private val extraThreads:()->List<Thread> = {emptyList()},
    private val slowNanos:Long=2_000_000_000L,
    private val reportIntervalNanos:Long=5_000_000_000L
) {
    enum class Phase(val label:String){LOCK_WAIT("lock-wait"),ENCRYPT("encrypt"),SOCKET_WRITE("socket-write"),SOCKET_FLUSH("socket-flush")}
    class Ticket internal constructor(internal val kind:String,internal val thread:Thread,internal val began:Long) {
        internal var phase=Phase.LOCK_WAIT
        internal var phaseBegan=began
        internal val phaseNanos=LongArray(Phase.entries.size)
        internal var stackSamples=0
    }
    data class Operation(val kind:String,val threadName:String,val threadId:Long,val phase:Phase,
        val elapsedNanos:Long,val phaseElapsedNanos:Long)
    data class Snapshot(val owner:Operation?,val operations:List<Operation>)
    private data class Report(val snapshot:Snapshot,val workers:List<Thread>,val captureExtra:Boolean)
    private val stateLock=Any()
    private val active=LinkedHashSet<Ticket>()
    private var lastReportNanos:Long?=null
    init{require(slowNanos>0&&reportIntervalNanos>0)}

    fun begin(kind:String):Ticket=Ticket(kind,Thread.currentThread(),nanoTime()).also{ticket->
        synchronized(stateLock){active.add(ticket)}
    }

    fun phase(ticket:Ticket,phase:Phase){
        val now=nanoTime()
        synchronized(stateLock){
            if(ticket !in active)return
            ticket.phaseNanos[ticket.phase.ordinal]+=(now-ticket.phaseBegan).coerceAtLeast(0)
            ticket.phase=phase;ticket.phaseBegan=now
        }
    }

    /** Cleanup also runs when encryption/write/flush throws; the original failure is never replaced. */
    fun <T> trace(kind:String,block:(Ticket)->T):T {
        val ticket=begin(kind)
        var failure:Throwable?=null
        try{return block(ticket)}catch(t:Throwable){failure=t;throw t}
        finally{complete(ticket,failure)}
    }

    fun complete(ticket:Ticket,failure:Throwable?=null){
        val now=nanoTime()
        val message=synchronized(stateLock){
            if(!active.remove(ticket))return
            ticket.phaseNanos[ticket.phase.ordinal]+=(now-ticket.phaseBegan).coerceAtLeast(0)
            val elapsed=(now-ticket.began).coerceAtLeast(0)
            if(elapsed<slowNanos)return
            "Video send ended kind="+safe(ticket.kind)+" thread="+safe(ticket.thread.name)+"#"+ticket.thread.id+
                " elapsedMs="+elapsed/1_000_000+" outcome="+(failure?.javaClass?.simpleName?:"recovered")+
                " finalPhase="+ticket.phase.label+" phaseMs="+Phase.entries.joinToString(","){
                    it.label+":"+ticket.phaseNanos[it.ordinal]/1_000_000}
        }
        emit(message)
    }

    fun snapshot():Snapshot {
        val now=nanoTime()
        return synchronized(stateLock){snapshotLocked(now)}
    }

    private fun snapshotLocked(now:Long):Snapshot {
        val operations=active.map{ticket->Operation(ticket.kind,ticket.thread.name,ticket.thread.id,ticket.phase,
            (now-ticket.began).coerceAtLeast(0),(now-ticket.phaseBegan).coerceAtLeast(0))}
        return Snapshot(operations.firstOrNull{it.phase!=Phase.LOCK_WAIT},operations)
    }

    /** Call from the existing heartbeat, not from the blocked sender. At most one diagnostic batch
     * per 5 s, with two stack samples per slow operation and three related threads/eight frames.
     * Every line stays below the application's 500-character log limit, including deep frames. */
    fun poll(){
        val now=nanoTime()
        val report=synchronized(stateLock){
            val slow=active.filter{now-it.began>=slowNanos}
            if(slow.isEmpty()||lastReportNanos?.let{now-it<reportIntervalNanos}==true)return
            lastReportNanos=now
            val capture=slow.any{it.stackSamples<MAX_STACK_SAMPLES}
            val workers=if(capture)active.filter{it.stackSamples<MAX_STACK_SAMPLES}.map{
                it.stackSamples++;it.thread
            }.distinct().take(MAX_THREADS) else emptyList()
            Report(snapshotLocked(now),workers,capture)
        }
        val description=report.snapshot.operations.take(MAX_THREADS).joinToString(";"){operation->
            safe(operation.kind,20)+"#"+operation.threadId+
                ":phase="+operation.phase.label+",elapsedMs="+operation.elapsedNanos/1_000_000+
                ",phaseMs="+operation.phaseElapsedNanos/1_000_000
        }
        val owner=report.snapshot.owner?.let{safe(it.kind,20)+"@"+safe(it.threadName,40)+"#"+it.threadId}?:"none"
        emit("Video send pending owner="+owner+" operations=["+description+"]"+
            if(report.captureExtra)" stacks=sampled" else " stacks=not-sampled")
        val extras=if(report.captureExtra)runCatching{extraThreads()}.getOrDefault(emptyList()) else emptyList()
        for(thread in (report.workers+extras).distinct().take(MAX_THREADS)){
            val frames=runCatching{stackCapture(thread).take(MAX_STACK_DEPTH).map{safe(it,MAX_FRAME_CHARS)}}
                .getOrElse{listOf("capture-failed:"+it.javaClass.simpleName)}
            val prefix="Video send stack thread="+safe(thread.name)+"#"+thread.id
            if(frames.isEmpty())emit(prefix+" frames=empty")
            else frames.forEachIndexed{index,frame->emit(prefix+" depth="+(index+1)+"/"+frames.size+" "+frame)}
        }
    }

    private fun emit(message:String){runCatching{log(message.take(MAX_LINE_CHARS))}}
    private fun safe(value:String,limit:Int=80)=value.replace('\n',' ').replace('\r',' ').take(limit)
    private companion object {
        const val MAX_STACK_DEPTH=8
        const val MAX_STACK_SAMPLES=2
        const val MAX_THREADS=3
        const val MAX_FRAME_CHARS=180
        const val MAX_LINE_CHARS=450
    }
}
