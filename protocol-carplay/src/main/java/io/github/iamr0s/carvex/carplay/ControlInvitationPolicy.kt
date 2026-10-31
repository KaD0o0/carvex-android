package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.RtspMessage
import java.net.InetAddress

/** An invitation is only a discovery signal, never a trusted identity or port.
 * Before an endpoint exists, constrain it to an on-link peer of the selected
 * network. Once candidates exist, retain exact address matching. */
object ControlInvitationPolicy {
    data class Local(val address:InetAddress,val prefixLength:Int)
    fun receiver(request:RtspMessage,peer:InetAddress,locals:List<Local>,known:Set<InetAddress>):ULong? {
        val parts=request.first.split(' ')
        if(parts.size!=3||parts[0]!="GET"||parts[1]!="/ctrl-int/1/connect"||
            parts[2] !in setOf("HTTP/1.0","HTTP/1.1","RTSP/1.0")||request.body.isNotEmpty())return null
        val text=request.headers["airplay-receiver-device-id"]?:return null
        if(text.isEmpty()||text.length>20||text.any{it !in '0'..'9'})return null
        val id=text.toULongOrNull()?:return null
        if(peer.isAnyLocalAddress||peer.isLoopbackAddress||peer.isMulticastAddress)return null
        if(locals.any{it.address.address.contentEquals(peer.address)})return null
        val allowed=if(known.isNotEmpty())known.any{it.address.contentEquals(peer.address)}
            else locals.any{onLink(it,peer)}
        return id.takeIf{allowed}
    }
    private fun onLink(local:Local,peer:InetAddress):Boolean {
        val a=local.address.address;val b=peer.address
        if(a.size!=b.size||local.prefixLength !in 1..a.size*8)return false
        for(bit in 0 until local.prefixLength){
            val mask=1 shl (7-bit%8)
            if((a[bit/8].toInt() and mask)!=(b[bit/8].toInt() and mask))return false
        }
        return true
    }
}
