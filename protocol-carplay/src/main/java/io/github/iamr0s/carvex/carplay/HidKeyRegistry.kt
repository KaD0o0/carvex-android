package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.carplay.core.*

class HidKeyDevice(val uuid:String?,val prefix:String,private val usages:HidUsages) {
    fun decode(report:ByteArray):Set<Int> = usages.decode(report)
}

/** Key devices are independent of display selection and touch coordinate mapping. */
internal class HidKeyRegistry {
    private val registered=mutableListOf<HidKeyDevice>()
    private val state=HidKeyState()
    fun register(uuid:String?,descriptor:ByteArray):List<HidKeyDevice> =
        runCatching{HidUsages.pages(descriptor)}.getOrDefault(emptySet()).mapNotNull{page->
            runCatching{HidUsages.parse(descriptor,page)}.getOrNull()?.let{
                HidKeyDevice(uuid,when(page){HidUsages.PAGE_CONSUMER->"media";HidUsages.PAGE_KEYBOARD->"keyboard";else->"page"+page.toString(16)},it)
            }
        }.also{registered.addAll(it)}
    fun devices(uuid:String?)=registered.filter{it.uuid==uuid}
    fun dispatch(device:HidKeyDevice,usages:Set<Int>,callback:(String,Int,Boolean)->Boolean)=
        state.dispatch(device.uuid,device.prefix,usages,callback)

    data class ReportResult(val recognized:Boolean,val accepted:Boolean,val touchMatched:Boolean,
        val decoded:List<Pair<String,Set<Int>>>,val decodeFailures:Int)
    /** A UUID may contain touch and key fields, in one report or different numbered reports.
     * Dispatch all matching fields; merely finding a registered UUID is not successful decoding. */
    fun report(uuid:String?,bytes:ByteArray,touch:HidTouch?,width:Int,height:Int,
               onTouch:(TouchSample)->Boolean,onKey:(String,Int,Boolean)->Boolean):ReportResult {
        var recognized=false;var accepted=true;var touched=false;var failures=0
        val decoded=mutableListOf<Pair<String,Set<Int>>>()
        if(touch?.matchesReport(bytes)==true){
            val sample=runCatching{touch.decode(bytes,width,height)}.getOrNull()
            if(sample==null)failures++ else {
                recognized=true;touched=true;accepted=onTouch(sample)&&accepted
            }
        }
        for(device in devices(uuid)){
            val usages=runCatching{device.decode(bytes)}.getOrNull()
            if(usages==null){failures++;continue}
            recognized=true;decoded+=device.prefix to usages
            accepted=dispatch(device,usages,onKey)&&accepted
        }
        return ReportResult(recognized,recognized&&accepted,touched,decoded,failures)
    }
}
