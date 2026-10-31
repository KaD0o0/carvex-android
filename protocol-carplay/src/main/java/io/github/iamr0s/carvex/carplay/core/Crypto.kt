package io.github.iamr0s.carvex.carplay.core
import java.security.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.agreement.X25519Agreement

object Crypto {
    val random=SecureRandom()
    fun random(n:Int)=ByteArray(n).also { random.nextBytes(it) }
    fun hash(vararg data:ByteArray)=MessageDigest.getInstance("SHA-512").run { data.forEach { update(it) }; digest() }
    fun hkdf(secret:ByteArray,salt:String,info:String):ByteArray {
        fun hmac(k:ByteArray,b:ByteArray)=Mac.getInstance("HmacSHA512").run {init(SecretKeySpec(k,"HmacSHA512")); doFinal(b)}
        val prk=hmac(salt.toByteArray(Charsets.US_ASCII),secret)
        return hmac(prk,info.toByteArray(Charsets.US_ASCII)+byteArrayOf(1)).copyOf(32)
    }
    fun nonce(n:Long):ByteArray { require(n>=0); return ByteArray(12).also { b-> repeat(8){b[it+4]=(n ushr (8*it)).toByte()} } }
    fun fixedNonce(s:String)=ByteArray(4)+s.toByteArray(Charsets.US_ASCII).also {require(it.size==8)}
    private fun crypt(encrypt:Boolean,key:ByteArray,nonce:ByteArray,b:ByteArray,aad:ByteArray):ByteArray {
        require(key.size==32&&nonce.size==12)
        val c=ChaCha20Poly1305(); c.init(encrypt,AEADParameters(KeyParameter(key),128,nonce,aad))
        val out=ByteArray(c.getOutputSize(b.size)); val n=c.processBytes(b,0,b.size,out,0)
        return out.copyOf(n+c.doFinal(out,n))
    }
    fun seal(key:ByteArray,nonce:ByteArray,b:ByteArray,aad:ByteArray=byteArrayOf())=crypt(true,key,nonce,b,aad)
    fun open(key:ByteArray,nonce:ByteArray,b:ByteArray,aad:ByteArray=byteArrayOf())=crypt(false,key,nonce,b,aad)
    fun public(seed:ByteArray)=Ed25519PrivateKeyParameters(seed,0).generatePublicKey().encoded
    fun sign(seed:ByteArray,b:ByteArray)=Ed25519Signer().run {init(true,Ed25519PrivateKeyParameters(seed,0)); update(b,0,b.size); generateSignature()}
    fun verify(key:ByteArray,b:ByteArray,sig:ByteArray):Boolean {
        if(key.size!=32||sig.size!=64)return false
        return Ed25519Signer().run {init(false,Ed25519PublicKeyParameters(key,0)); update(b,0,b.size); verifySignature(sig)}
    }
    fun xPublic(secret:ByteArray):ByteArray {require(secret.size==32);return X25519PrivateKeyParameters(secret,0).generatePublicKey().encoded}
    fun shared(secret:ByteArray,public:ByteArray):ByteArray {
        require(secret.size==32&&public.size==32); val a=X25519Agreement(); a.init(X25519PrivateKeyParameters(secret,0))
        return ByteArray(32).also {a.calculateAgreement(X25519PublicKeyParameters(public,0),it,0); require(it.any {v->v!=0.toByte()})}
    }
}
