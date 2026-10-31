package io.github.iamr0s.carvex.carplay.core
/** Credentials stay private; security enums belong to their respective message schemas. */
class WifiAnnouncement(val ssid:String?,val password:String?,val security:Int?,val channel:Int?,val bssid:ByteArray?=null) {
    fun matches(ssid:String,password:String){
        require(this.ssid==null||this.ssid==ssid){"5703/4301 Wi-Fi credentials conflict; renegotiation required"}
        require(this.password==null||this.password==password){"5703/4301 Wi-Fi credentials conflict; renegotiation required"}
    }
    /** This mapping is for 5703-only discovery, not for the separate 4301 schema. */
    fun requireNetworkOnly() {
        if(ssid==null)throw WifiConfigurationException(1,WifiConfigurationProblem.MISSING_FIELD)
        // This is Android's usable-network policy, not the iAP2 string getter.
        val ssidBytes=ssid.toByteArray(Charsets.UTF_8).size
        if(ssid.isBlank()||ssidBytes>32)throw WifiConfigurationException(1,WifiConfigurationProblem.INVALID_SSID,actualBytes=ssidBytes)
        val securityValue=security?:throw WifiConfigurationException(3,WifiConfigurationProblem.MISSING_FIELD)
        if(securityValue !in setOf(0,2,3,4))
            throw WifiConfigurationException(3,WifiConfigurationProblem.UNSUPPORTED_SECURITY,value=securityValue)
        if(securityValue!=0){
            val pass=password?:throw WifiConfigurationException(2,WifiConfigurationProblem.MISSING_FIELD)
            val bytes=pass.toByteArray(Charsets.UTF_8).size
            if(bytes !in 8..63)throw WifiConfigurationException(2,WifiConfigurationProblem.INVALID_PASSPHRASE_LENGTH,actualBytes=bytes)
        }
    }
    companion object {
        fun parse(fields:IapFields,log:(String)->Unit={}):WifiAnnouncement {
            log("5703 field lengths="+fields.ordered.joinToString(","){(id,b)->"$id:${b.size}"})
            fun byte(id:Int,b:ByteArray):Int {
                if(b.size!=1)throw WifiConfigurationException(id,WifiConfigurationProblem.INVALID_WIDTH,actualBytes=b.size)
                return b[0].toInt() and 255
            }
            fun text(id:Int,b:ByteArray):String {
                // iAP2MsgGetDataAsString@0x100067ee4 checks the final byte,
                // not whether any earlier byte happens to be NUL.
                if(b.isEmpty()||b.last()!=0.toByte())throw WifiConfigurationException(id,WifiConfigurationProblem.MISSING_NUL,actualBytes=b.size)
                return IapWire.text(b)
            }
            var bssid:ByteArray?=null
            var ssid:String?=null
            var password:String?=null
            var security:Int?=null
            var channel:Int?=null
            for((id,raw) in fields.ordered){
                when(id){
                    0 -> bssid=raw.copyOf()
                    1 -> ssid=text(id,raw)
                    2 -> password=text(id,raw)
                    3 -> security=byte(id,raw)
                    4 -> channel=byte(id,raw)
                    5,6 -> Unit
                    else -> log("Unrecognized paramID:"+String.format(java.util.Locale.ROOT,"0x%04X",id))
                }
            }
            val result=WifiAnnouncement(ssid,password,security,channel,bssid)
            log("5703 parsed security="+result.security+" channel="+result.channel)
            return result
        }
    }
}

enum class WifiConfigurationProblem {
    MISSING_FIELD, DUPLICATE_FIELD, INVALID_WIDTH, MISSING_NUL, INVALID_SSID,
    UNSUPPORTED_SECURITY, INVALID_PASSPHRASE_LENGTH
}
/** Metadata only: never accepts or prints SSID/passphrase contents. */
class WifiConfigurationException(val fieldId:Int,val problem:WifiConfigurationProblem,
    val actualBytes:Int?=null,val value:Int?=null):IllegalArgumentException(
    "5703 invalid Wi-Fi configuration: field="+fieldId+" reason="+problem.name+
        " bytes="+actualBytes+" value="+value)
