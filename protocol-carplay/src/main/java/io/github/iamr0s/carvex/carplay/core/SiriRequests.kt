package io.github.iamr0s.carvex.carplay.core
import java.security.MessageDigest

/** A separate instance per control/event connection epoch. CSeq only identifies a request here.
 * Explicit event compatibility may accept an absent CSeq without inventing a replay identity. */
class SiriRequests(private val allowMissingCSeq:Boolean=false,private val dispatch:(Int)->Boolean) {
    private data class Reply(val digest:ByteArray,val status:Int)
    private val replies=linkedMapOf<String,Reply>()
    @Synchronized fun handle(request:RtspMessage):Pair<Int,ByteArray> {
        if(request.first.split(' ').take(2)!=listOf("POST","/command"))return 400 to byteArrayOf()
        val seq=request.headers["cseq"]
        if(seq==null){if(!allowMissingCSeq)return 400 to byteArrayOf()}
        else if(seq.isEmpty()||seq.length>10||seq.any{it !in '0'..'9'})return 400 to byteArrayOf()
        if(request.body.size !in 8..4096||!request.body.copyOfRange(0,8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII)))
            return 400 to byteArrayOf()
        val digest=MessageDigest.getInstance("SHA-256").digest(request.body)
        if(seq!=null)replies[seq]?.let{return (if(MessageDigest.isEqual(digest,it.digest))it.status else 409) to byteArrayOf()}
        val command=runCatching{Plist.dictionary(request.body)}.getOrNull()?:return 400 to byteArrayOf()
        val action=SiriCommand.action(command)
        val status=if(action!=null&&dispatch(action))200 else 501
        if(seq!=null){
            if(replies.size>=64)replies.remove(replies.keys.first())
            replies[seq]=Reply(digest,status)
        }
        return status to byteArrayOf()
    }
    @Synchronized fun reset(){replies.clear()}
}
