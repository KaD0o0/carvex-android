package io.github.iamr0s.carvex.carplay.core

import java.net.InetAddress
import java.net.Inet6Address

/** Numeric peer addresses only; never turn an untrusted CSM field into a DNS lookup. */
object IpLiteral {
    data class Parsed(val address:InetAddress,val scope:String?)
    /** Equivalent numeric spellings only. Scope is assigned from the selected local interface,
     * not the accessory's interface name. This never resolves a DNS name. */
    fun normalizeCompatible(text:String):String {
        require(text.length<=160)
        var value=text.trim()
        if(value.startsWith('[')){
            require(value.endsWith(']'))
            value=value.substring(1,value.length-1)
            require(':' in value)
        }
        val parts=value.split('%');require(parts.size in 1..2)
        val raw=parts[0]
        if(':' !in raw){
            require(parts.size==1)
            val octets=raw.split('.');require(octets.size==4)
            value=octets.joinToString("."){
                require(it.length in 1..3&&it.all{c->c in '0'..'9'})
                it.toInt().also{number->require(number in 0..255)}.toString()
            }
        }
        // IPv4-mapped IPv6 literals may be returned as Inet4Address by the JVM.
        if(':' in raw&&parts.size==1){
            require(raw.all{it in "0123456789abcdefABCDEF:."})
            val address=try{InetAddress.getByName(raw)}catch(t:java.net.UnknownHostException){throw IllegalArgumentException(t)}
            value=requireNotNull(address.hostAddress)
        }
        return requireNotNull(parse(value).address.hostAddress)
    }
    fun parse(text:String):Parsed {
        require(text.isNotBlank()&&text.length<=128&&text.none{it.isWhitespace()||it.isISOControl()})
        val parts=text.split('%');require(parts.size in 1..2)
        val literal=parts[0]
        val scope=parts.getOrNull(1)?.also{require(it.isNotEmpty()&&it.all{c->c.isLetterOrDigit()||c in "_.-"})}
        val ipv6=':' in literal
        if(ipv6)require(literal.all{it in "0123456789abcdefABCDEF:."})
        else {
            require(scope==null)
            val octets=literal.split('.');require(octets.size==4)
            require(octets.all{it.isNotEmpty()&&it.length<=3&&it.all{c->c in '0'..'9'}&&it.toInt() in 0..255&&(it.length==1||it[0]!='0')})
        }
        val address=try{InetAddress.getByName(literal)}catch(error:java.net.UnknownHostException){throw IllegalArgumentException(error)}
        require(!ipv6||address is Inet6Address)
        require(!address.isAnyLocalAddress&&!address.isLoopbackAddress&&!address.isMulticastAddress)
        require(scope==null||address.isLinkLocalAddress)
        return Parsed(address,scope)
    }
}
