package io.github.iamr0s.carvex.carplay.core
/** Metadata is untrusted until endpoint, ID and any pinned keys have been reconciled. */
object ReceiverIdentity {
    fun canonical(value:String)=value.replace(":","").replace("-","").lowercase().also{require(it.matches(Regex("[0-9a-f]{12}"))){"Invalid receiver device ID"}}
    /** Observed representations in the supplied receiver implementations; never arbitrary UUIDs. */
    fun invitationCandidates(value:String?):Set<String> {
        if(value==null)return emptySet()
        val text=value.trim()
        if(text.length !in 1..32)return emptySet()
        val result=linkedSetOf<String>()
        if(text.matches(Regex("[0-9a-fA-F]{12}"))||text.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")))result.add(canonical(text))
        if(text.all{it in '0'..'9'})text.toULongOrNull()?.takeIf{it<=0xffffffffffffUL}?.let{result.add(it.toString(16).padStart(12,'0'))}
        return result
    }
    fun publicKey(value:String?):ByteArray?=value?.let{
        require(it.matches(Regex("[0-9a-fA-F]{64}"))){"Invalid Bonjour public key"}
        it.chunked(2).map{part->part.toInt(16).toByte()}.toByteArray()
    }
    fun reconcile(first:ByteArray?,second:ByteArray?):ByteArray? {
        require(first==null||second==null||java.security.MessageDigest.isEqual(first,second)){"Receiver key conflict between 4301 and Bonjour"}
        return first?:second
    }
    fun features(value:String?):ULong? {
        if(value==null)return null
        val parts=value.split(',');if(parts.size !in 1..2)return null
        fun part(s:String)=s.trim().removePrefix("0x").removePrefix("0X").toULongOrNull(16)
        val low=part(parts[0])?:return null
        if(parts.size==1)return low
        val high=part(parts[1])?:return null
        if(low>0xffffffffUL||high>0xffffffffUL)return null
        return low or (high shl 32)
    }
}
