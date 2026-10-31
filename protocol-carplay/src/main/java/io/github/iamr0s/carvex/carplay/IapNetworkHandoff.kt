package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** One attempt's inputs, not a credential cache or an authentication provider.
 * Bootstrap owns the reader until it returns; the caller then owns exactly one
 * message pump. Neither callback does Android/network work on that reader. */
class IapNetworkHandoff(
    private val generation:Int,
    private val epoch:Long,
    private val experimental:Boolean,
    private val current:()->Boolean,
    private val log:(String)->Unit={},
):AutoCloseable {
    private data class State(val configuration:WifiBootstrap?=null,val closed:Boolean=false)
    private val state=MutableStateFlow(State())
    private var accepted:WifiBootstrap?=null
    private var committed=false

    // Android's existing join mapping treats 2/3 as WPA2. This is NOT an
    // assertion that the two iAP message schemas use equivalent Apple enums.
    private fun sameNetwork(a:WifiBootstrap,b:WifiBootstrap):Boolean =
        a.ssid==b.ssid&&a.password==b.password&&
            (a.security==b.security||(a.security in 2..3&&b.security in 2..3))

    @Synchronized fun requireCurrent(){
        check(!state.value.closed&&current()){"Network handoff context retired"}
        accepted?.identification?.requireCurrent(generation,epoch)
    }
    @Synchronized fun configuration():WifiBootstrap? {requireCurrent();return state.value.configuration}

    @Synchronized fun announcement(value:WifiAnnouncement?){
        requireCurrent()
        if(value==null)return
        val candidate=try{WifiBootstrap.networkOnly(value,emptySet())}
            catch(t:IllegalArgumentException){log("Handoff 5703 has no usable network; awaiting correction");return}
        offer(candidate,"5703")
    }

    @Synchronized fun startRequest(fields:IapFields){
        requireCurrent()
        // Check the whole message before extracting even its network-only subset.
        val normalized=try{StartSessionSyntax.normalize(fields)}
            catch(t:IllegalArgumentException){log("Handoff 4301 syntax rejected; current candidate unchanged");return}
        val candidate=try{Bootstrap.wifiConfig(normalized,null,null,experimental,log)}
            catch(t:StartSessionConfigurationException){
                if(t.problem!=StartSessionProblem.MISSING_FIELD){
                    log("Handoff 4301 endpoint rejected reason="+t.problem);return
                }
                try{Bootstrap.networkConfig(normalized)}catch(t:IllegalArgumentException){
                    log("Handoff 4301 is partial; awaiting usable configuration");return
                }
            }catch(t:IllegalArgumentException){log("Handoff 4301 local configuration rejected");return}
        offer(candidate,"4301")
    }

    private fun offer(candidate:WifiBootstrap,source:String){
        val old=state.value.configuration
        if(old!=null&&!sameNetwork(old,candidate)){
            // Never redirect an in-flight lease to a different credential set.
            log("Handoff configuration conflict; a new attempt is required");return
        }
        if(committed){log("Handoff input after endpoint commitment; live session unchanged");return}
        val endpoint=candidate.endpoint?:old?.endpoint
        val base=accepted?:old?:candidate
        val next=base.copy(endpoint=endpoint,startSession=candidate.startSession?:old?.startSession)
        state.value=State(next)
        log("Handoff input="+source+" networkReady=true endpointReady="+(endpoint!=null))
    }

    /** Called only with Bootstrap's current-link authentication/Identification result. */
    @Synchronized fun acceptBootstrap(value:WifiBootstrap){
        requireCurrent()
        val auth=requireNotNull(value.authentication)
        check(auth.generation==generation&&auth.epoch==epoch&&auth.accepted(experimental)){
            "Network handoff requires current bootstrap authentication"}
        val identity=requireNotNull(value.identification)
        identity.requireCurrent(generation,epoch)
        check(accepted==null){"Bootstrap already attached"}
        state.value.configuration?.let{old->
            check(old.ssid==value.ssid&&old.password==value.password&&old.security==value.security){
                "Bootstrap configuration differs from current handoff"}
        }
        accepted=value
        offer(value,"bootstrap")
    }

    /** Only declared, authenticated current-link messages may update the candidate. */
    @Synchronized fun handle(command:IapCommand,currentGeneration:Int,currentEpoch:Long):Boolean {
        if(command.id!=0x5703&&command.id!=0x4301)return false
        requireCurrent()
        check(currentGeneration==generation&&currentEpoch==epoch){"Stale network handoff event"}
        val base=requireNotNull(accepted){"Network event before bootstrap acceptance"}
        if(base.identification!!.capabilities.sentByAccessory.none{it.value==command.id}){
            log("Handoff unadvertised input ignored id="+command.id.toString(16));return true
        }
        val fields=command.fields?:return true
        if(command.id==0x4301)startRequest(fields)
        else {
            val parsed=try{WifiAnnouncement.parse(fields)}catch(t:IllegalArgumentException){
                log("Handoff malformed 5703 ignored");return true
            }
            announcement(parsed)
        }
        return true
    }

    suspend fun awaitEndpoint():WifiBootstrap {
        state.first{it.closed||it.configuration?.endpoint!=null}
        return synchronized(this){requireCurrent();requireNotNull(state.value.configuration)}
    }

    @Synchronized fun canAwaitStart():Boolean = accepted?.identification?.capabilities?.networkHandoff?.carPlayStartSession==true

    /** No live socket mutation: selection is frozen at the AirSession boundary. */
    @Synchronized fun commit(selected:WifiBootstrap):WifiBootstrap {
        requireCurrent()
        val base=requireNotNull(accepted)
        check(selected.ssid==base.ssid&&selected.password==base.password&&selected.security==base.security)
        check(selected.authentication==base.authentication&&selected.identification===base.identification)
        requireNotNull(selected.endpoint)
        check(!committed)
        val result=state.value.configuration?.takeIf{it.endpoint!=null}?:selected
        committed=true
        state.value=State(result)
        return result
    }

    @Synchronized override fun close(){state.value=State(closed=true)}
}
