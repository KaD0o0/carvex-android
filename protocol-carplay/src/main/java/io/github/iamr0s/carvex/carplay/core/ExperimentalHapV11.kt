package io.github.iamr0s.carvex.carplay.core
import java.security.MessageDigest

/** Device identifiers and HAP pairing identifiers are independent namespaces. */
object PairingIdentityBinding {
    /** 固件遇到 `/info` 的 deviceID 与 Bonjour deviceID 不一致时，**保留原值并继续**
     *  （日志 "Ignoring new ID … and using original"）。这里同样不中止，只置标志供调用方记录。 */
    @Volatile var lastDeviceIdConflict:Boolean=false
    fun identifier(deviceId:String?,bonjourDeviceId:String?,pairingId:String?):ByteArray {
        lastDeviceIdConflict=deviceId!=null&&bonjourDeviceId!=null&&
            ReceiverIdentity.canonical(deviceId)!=ReceiverIdentity.canonical(bonjourDeviceId)
        return pairingId?.toByteArray(Charsets.UTF_8)?.takeIf{it.size in 1..1024}
            ?:securityFailure(SecurityReason.PAIR_IDENTIFIER_MISSING)
    }
    fun requirePeer(identifier:ByteArray,advertised:ByteArray?,peer:PeerIdentity) {
        if(!identifier.contentEquals(peer.identifier)||
            (advertised!=null&&!MessageDigest.isEqual(advertised,peer.public)))
            securityFailure(SecurityReason.PEER_BINDING_CONFLICT)
    }
}

/** Compatibility with commit 574d7d2, enabled only by the existing experiment option.
 * This proves HAP possession; it does not establish formal AA/SAP certificate trust. */
object ExperimentalHapV11 {
    fun srp()=PairSetupSrp(dialect=PairSetupProof.EXPERIMENTAL_V11_PADDED)
    fun authenticate(identifier:ByteArray,advertised:ByteArray?,previous:PeerIdentity?,
                     setup:(ByteArray)->PeerIdentity,verify:(PeerIdentity)->ByteArray,
                     saveVerified:(PeerIdentity)->Unit):ByteArray {
        val target=previous?:run {
            val code="3939".toByteArray(Charsets.US_ASCII)
            try{setup(code)}finally{code.fill(0)}
        }
        PairingIdentityBinding.requirePeer(identifier,advertised,target)
        val shared=verify(target)
        try{saveVerified(target)}catch(t:Throwable){shared.fill(0);throw t}
        return shared
    }
}

/** Encrypted by PairStore. Separate namespace; never a production ACTIVE record.
 * Read old aliases only for this receiver, then validate their original identifier bytes. */
class ExperimentalHapRecords(private val storage:PairRecordStorage) {
    private fun key(owner:PairIdentity,receiver:String,identifier:ByteArray)=
        "experiment-hap-v11-"+PairRecords(storage).key(owner,identifier,"hap-v11:"+receiver,1)
    private fun decode(raw:ByteArray):PeerIdentity = try {
        val p=Plist.dictionary(raw)
        val id=p["identifier"] as? ByteArray ?: (p["id"] as String).toByteArray(Charsets.UTF_8)
        PeerIdentity(id.toString(Charsets.UTF_8),p["public"] as ByteArray,id)
    }catch(t:SecurityFailure){throw t}
     catch(t:Exception){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","legacy-record",SecurityReason.PAIR_STORE_CORRUPT),cause=t)}
    fun load(owner:PairIdentity,receiver:String,pairingId:String):PeerIdentity? {
        val id=pairingId.toByteArray(Charsets.UTF_8)
        storage.read(key(owner,receiver,id))?.let{return decode(it).also{p->PairingIdentityBinding.requirePeer(id,null,p)}}
        val exact="peer-"+PairAlias.primary(receiver,pairingId)
        storage.read(exact)?.let{return decode(it).also{p->PairingIdentityBinding.requirePeer(id,null,p)}}
        val fallback="peer-"+PairAlias.fallback(receiver)
        if(fallback!=exact)storage.read(fallback)?.let{
            val peer=decode(it)
            if(peer.identifier.contentEquals(id))return peer
        }
        return null
    }
    @Synchronized fun saveVerified(owner:PairIdentity,receiver:String,pairingId:String,peer:PeerIdentity) {
        val id=pairingId.toByteArray(Charsets.UTF_8)
        PairingIdentityBinding.requirePeer(id,null,peer)
        load(owner,receiver,pairingId)?.let{PairingIdentityBinding.requirePeer(id,it.public,peer)}
        storage.write(key(owner,receiver,id),Plist.encode(mapOf("identifier" to peer.identifier,"public" to peer.public)))
    }
}
