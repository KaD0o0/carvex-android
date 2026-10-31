package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.carplay.core.*
import java.nio.ByteBuffer

enum class StartSessionProblem { MISSING_FIELD,DUPLICATE_FIELD,INVALID_GROUP,INVALID_WIDTH,INVALID_TEXT,INVALID_BOOLEAN,
    INVALID_ADDRESS,INVALID_PORT,INVALID_IDENTIFIER,INVALID_PUBLIC_KEY,INVALID_SSID,UNSUPPORTED_SECURITY,INVALID_PASSPHRASE_LENGTH }

/** Never logs credential, address, identity or public-key contents. */
class StartSessionConfigurationException(val fieldPath:String,val problem:StartSessionProblem,val actualBytes:Int?=null):
    IllegalArgumentException("4301 invalid start configuration: field="+fieldPath+" reason="+problem.name+" bytes="+actualBytes)

internal class StartSessionFields(private val fields:IapFields,private val path:String="") {
    fun name(id:Int)=if(path.isEmpty())id.toString()else "$path.$id"
    fun fail(id:Int,problem:StartSessionProblem,bytes:Int?=null):Nothing =
        throw StartSessionConfigurationException(name(id),problem,bytes)
    fun optional(id:Int):ByteArray? {
        val entries=fields.ordered.filter{it.first==id}
        if(entries.size>1)fail(id,StartSessionProblem.DUPLICATE_FIELD)
        return entries.singleOrNull()?.second
    }
    fun required(id:Int):ByteArray=optional(id)?:fail(id,StartSessionProblem.MISSING_FIELD)
    fun text(id:Int):String=decodeText(id,required(id))
    fun decodeText(id:Int,bytes:ByteArray):String = try{IapWire.text(bytes)}
        catch(t:IllegalArgumentException){fail(id,StartSessionProblem.INVALID_TEXT,bytes.size)}
    fun group(id:Int):StartSessionFields {
        val raw=required(id)
        return try{StartSessionFields(IapWire.parseFieldList(raw),name(id))}
            catch(t:IllegalArgumentException){fail(id,StartSessionProblem.INVALID_GROUP,raw.size)}
    }
    fun byte(id:Int):Int {
        val raw=required(id)
        if(raw.size!=1)fail(id,StartSessionProblem.INVALID_WIDTH,raw.size)
        return raw[0].toInt() and 255
    }
    fun port(id:Int):Int {
        val raw=required(id)
        if(raw.size!=4)fail(id,StartSessionProblem.INVALID_WIDTH,raw.size)
        return ByteBuffer.wrap(raw).int.also{if(it !in 1..65535)fail(id,StartSessionProblem.INVALID_PORT,raw.size)}
    }
    fun keyHint(id:Int,compatible:Boolean,log:(String)->Unit):ByteArray? {
        val raw=optional(id)?:return null
        val key=runCatching{ReceiverIdentity.publicKey(decodeText(id,raw))}.getOrNull()
        if(key==null){
            if(!compatible)fail(id,StartSessionProblem.INVALID_PUBLIC_KEY,raw.size)
            log("4301 optional public-key hint ignored; unsupported encoding; bytes="+raw.size)
        }
        return key
    }
    fun addresses(id:Int,compatible:Boolean=false,log:(String)->Unit={}):List<String> {
        val entries=fields.ordered.filter{it.first==id}
        if(entries.isEmpty())fail(id,StartSessionProblem.MISSING_FIELD)
        val accepted=entries.mapNotNull{(_,raw)->
            try{
                val text=decodeText(id,raw)
                if(compatible)IpLiteral.normalizeCompatible(text)else text.also{IpLiteral.parse(it)}
            }catch(t:IllegalArgumentException){
                if(!compatible)fail(id,StartSessionProblem.INVALID_ADDRESS,raw.size)
                log("4301 unusable address candidate skipped; field="+name(id)+" bytes="+raw.size)
                null
            }
        }.distinct()
        if(accepted.isEmpty())fail(id,StartSessionProblem.INVALID_ADDRESS)
        return accepted
    }
    fun shape()=fields.ordered.joinToString(","){(id,raw)->"$id:${raw.size}"}
}
