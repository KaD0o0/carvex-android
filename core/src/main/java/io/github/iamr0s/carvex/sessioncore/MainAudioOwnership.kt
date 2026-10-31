package io.github.iamr0s.carvex.sessioncore
/** Who owns the CarPlay main audio stream, as far as this session can tell.
 *
 * The receiver may take main audio for itself (its own prompt, its own media) and says so through the
 * changeModes/audioOwner resource handshake. Until ownership is actually settled the state stays
 * UNKNOWN and main output is withheld: sending main audio while the car also owns the stream produces
 * two overlapping main streams, which is heard as doubled, level-jumping audio while the log
 * afterwards shows nothing unusual.
 *
 * UNKNOWN is never left by waiting. It ends when one of these is true:
 *  - the receiver advertises no type-100 main audio stream at all (OURS), or
 *  - we announced our own hold over the control session and it was accepted (OURS), or
 *  - the receiver declared ownership in changeModes (OURS/THEIRS).
 *
 * [log] is the session log. [announceHold] performs the modesChanged round trip and reports whether
 * it was accepted; it may throw, because an unreachable control session is not an ownership decision.
 */
class MainAudioOwnership(private val log:(String)->Unit,private val announceHold:suspend (Boolean)->Boolean) {
    enum class State { UNKNOWN,OURS,THEIRS }
    private var state=State.UNKNOWN
    private var announced=false

    @Synchronized fun state():State=state
    /** True while our own hold is announced and accepted, so a second announcement is not sent. */
    @Synchronized fun ownHoldAccepted():Boolean=announced

    /** Diagnostic only: no timeout/retry or ownership change is introduced here. */
    private suspend fun announceObserved(phone:Boolean):Boolean {
        val began=System.nanoTime()
        log("MainAudio transition phase=begin phone=$phone monoNs=$began owner="+state().name.lowercase())
        try{
            val accepted=announceHold(phone)
            log("MainAudio transition phase=end phone=$phone accepted=$accepted elapsedMs="+
                (System.nanoTime()-began)/1_000_000)
            return accepted
        }catch(t:Throwable){
            log("MainAudio transition phase=failed phone=$phone elapsedMs="+
                (System.nanoTime()-began)/1_000_000+" error="+t.javaClass.simpleName)
            throw t
        }
    }

    /** The receiver said whether it advertises a main audio stream (type 100) in its /info. */
    @Synchronized fun receiverAdvertised(mainStreamAdvertised:Boolean){
        if(!mainStreamAdvertised&&state==State.UNKNOWN){
            state=State.OURS
            log("MainAudio owner=ours; receiver advertises no main audio stream")
        }
    }
    /** changeModes declared who owns the main audio resource; audioOwner==1 means the phone (us). */
    @Synchronized fun receiverDeclared(receiverOwns:Boolean){
        val next=if(receiverOwns)State.THEIRS else State.OURS
        if(state!=next){
            // The receiver's declaration is authoritative: a hold of ours it just took over is gone.
            announced=!receiverOwns
            state=next
            log("MainAudio owner="+next.name.lowercase()+" receiverDeclared="+receiverOwns)
        }
    }
    /** Settle ownership before a main stream is set up: throws instead of opening a second main stream. */
    suspend fun takeOwn(){
        when(synchronized(this){state}){
            State.THEIRS->throw IllegalStateException("Main audio is owned by the receiver; new main SETUP suppressed")
            State.OURS->if(synchronized(this){announced})return
            State.UNKNOWN->Unit
        }
        if(!announceObserved(true)){
            synchronized(this){state=State.UNKNOWN;announced=false}
            throw IllegalStateException("Main audio ownership announcement was rejected")
        }
        synchronized(this){state=State.OURS;announced=true;log("MainAudio owner=ours; own hold announced and accepted")}
    }
    /** Drop our hold on main audio; ownership goes back to unknown for the next attempt. */
    suspend fun releaseOwn(){
        if(!synchronized(this){announced})return
        synchronized(this){announced=false;state=State.UNKNOWN;log("MainAudio owner=unknown; own hold released")}
        if(!announceObserved(false))log("MainAudio owner release was not accepted; state stays unknown")
    }
    /** Categories 1 and 3 send only while we hold main audio; unknown and receiver-held both withhold. */
    @Synchronized fun allowsMainSend():Boolean=state==State.OURS
}
