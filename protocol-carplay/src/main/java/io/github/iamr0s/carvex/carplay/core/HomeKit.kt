package io.github.iamr0s.carvex.carplay.core
import java.security.MessageDigest

data class PairIdentity(val id:String,val seed:ByteArray){init{require(id.toByteArray(Charsets.UTF_8).size in 1..1024&&seed.size==32)}}
/** The string is an alias for storage/display. Signatures always use the original identifier bytes. */
data class PeerIdentity(val id:String,val public:ByteArray,val identifier:ByteArray=id.toByteArray(Charsets.UTF_8)){
    init{require(identifier.size in 1..1024&&public.size==32)}
}
class HomeKit(private val client:RtspClient,private val identity:PairIdentity,private val srpFactory:()->PairSetupSrp={PairSetupSrp()}) {
    private fun exchange(path:String,fields:ByteArray,state:Int):Map<Int,ByteArray>{
        val r=client.request("POST",path,fields,"application/pairing+tlv8",
            if(path=="/pair-verify")mapOf("X-Apple-HKP" to "2","X-Apple-PD" to "1")else mapOf("X-Apple-HKP" to "0"))
        if(r.status !in 200..299)throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","M$state",SecurityReason.AIRPLAY_REJECTED,wireStatus=r.status))
        val t=Tlv8.decode(r.body)
        PairingResponses.validate(t,state,path==SETUP_PATH,r.status)
        return t
    }
    private val SETUP_PATH="/pair-setup"
    private fun openPair(key:ByteArray,nonce:ByteArray,body:ByteArray):ByteArray = try{Crypto.open(key,nonce,body)}
        catch(t:Exception){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","decrypt",SecurityReason.PAIR_TAG_INVALID),cause=t)}

    fun setup(advertised:ByteArray?=null,setupCode:ByteArray?=null):PeerIdentity {
        // Exact bytes must come from the selected schema provider, not a universal PIN UI.
        if(setupCode==null||setupCode.isEmpty())throw PairUnsupported()
        val srp=srpFactory()
        fun text(s:String)=s.toByteArray(Charsets.UTF_8)
        try{
        val m2=exchange("/pair-setup",Tlv8.encode(0 to byteArrayOf(0),Tlv8.state(1)),2)
        val result=srp.respond(requireNotNull(m2[2]),requireNotNull(m2[3]),setupCode)
        val K=result.key
        try{
        val m4=exchange("/pair-setup",Tlv8.encode(Tlv8.state(3),3 to srp.public,4 to result.client),4)
        if(!MessageDigest.isEqual(requireNotNull(m4[4]),result.server))securityFailure(SecurityReason.PAIR_PROOF_INVALID,phase="M4")
        val enc=Crypto.hkdf(K,"Pair-Setup-Encrypt-Salt","Pair-Setup-Encrypt-Info")
        val pub=Crypto.public(identity.seed);val id=text(identity.id)
        val signKey=Crypto.hkdf(K,"Pair-Setup-Controller-Sign-Salt","Pair-Setup-Controller-Sign-Info")
        val sub=Tlv8.encode(1 to id,3 to pub,10 to Crypto.sign(identity.seed,signKey+id+pub))
        val m6=exchange("/pair-setup",Tlv8.encode(Tlv8.state(5),5 to Crypto.seal(enc,Crypto.fixedNonce("PS-Msg05"),sub)),6)
        val peer=Tlv8.decode(openPair(enc,Crypto.fixedNonce("PS-Msg06"),requireNotNull(m6[5])))
        val peerId=requireNotNull(peer[1]).also{require(it.size in 1..1024)};val peerPublic=requireNotNull(peer[3]).also{require(it.size==32)}
        require(advertised==null || MessageDigest.isEqual(advertised,peerPublic)){"Advertised peer key mismatch"}
        val key=Crypto.hkdf(K,"Pair-Setup-Accessory-Sign-Salt","Pair-Setup-Accessory-Sign-Info")
        if(!Crypto.verify(peerPublic,key+peerId+peerPublic,requireNotNull(peer[10])))securityFailure(SecurityReason.PAIR_SIGNATURE_INVALID,phase="M6")
        return PeerIdentity(peerId.toString(Charsets.UTF_8),peerPublic,peerId.copyOf())
        }finally{result.close()}
        }finally{srp.close()}
    }
    fun verify(peer:PeerIdentity):ByteArray {
        val random=Crypto.random(32)
        val secret=try{Crypto.hkdf(random,"Pair-Verify-ECDH-Salt","Pair-Verify-ECDH-Info")}finally{random.fill(0)}
        var complete=false
        var sharedSecret:ByteArray?=null
        var encryptionKey:ByteArray?=null
        try{
        val c=Crypto.xPublic(secret)
        val m2=exchange("/pair-verify",Tlv8.encode(Tlv8.state(1),3 to c),2)
        val s=requireNotNull(m2[3]).also{require(it.size==32)};val shared=Crypto.shared(secret,s);sharedSecret=shared
        val key=Crypto.hkdf(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info")
        val sub=Tlv8.decode(openPair(key.also{encryptionKey=it},Crypto.fixedNonce("PV-Msg02"),requireNotNull(m2[5]).also{require(it.size>16)}))
        val peerId=requireNotNull(sub[1]).also{require(it.size in 1..1024)}
        if(!MessageDigest.isEqual(peerId,peer.identifier)||!Crypto.verify(peer.public,s+peerId+c,requireNotNull(sub[10])))
            securityFailure(SecurityReason.PAIR_SIGNATURE_INVALID,phase="verify-M2")
        val id=identity.id.toByteArray(Charsets.UTF_8)
        val own=Tlv8.encode(1 to id,10 to Crypto.sign(identity.seed,c+id+s))
        exchange("/pair-verify",Tlv8.encode(Tlv8.state(3),5 to Crypto.seal(key,Crypto.fixedNonce("PV-Msg03"),own)),4)
        client.encrypted(shared)
        complete=true
        return shared
        }finally{secret.fill(0);encryptionKey?.fill(0);if(!complete)sharedSecret?.fill(0)}
    }
}

class PairUnsupported(message:String="Pair endpoint unsupported"):java.io.IOException(message)
