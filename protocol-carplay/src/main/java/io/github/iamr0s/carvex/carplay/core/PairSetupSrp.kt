package io.github.iamr0s.carvex.carplay.core
import java.math.BigInteger
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups

enum class PairSetupProof { MINIMAL_V1, EXPERIMENTAL_V11_PADDED }

/** The dialect is selected before M1; a failed proof never triggers another dialect. */
class PairSetupSrp(privateBytes:ByteArray=Crypto.random(48),private val dialect:PairSetupProof=PairSetupProof.MINIMAL_V1):AutoCloseable {
    private var privateValue:BigInteger?=BigInteger(1,privateBytes).also{require(privateBytes.size in 32..384&&it.signum()>0)}
    val public:ByteArray=g.modPow(requireNotNull(privateValue),n).let{if(dialect==PairSetupProof.EXPERIMENTAL_V11_PADDED)pad(it)else min(it)}
    data class Proof(val key:ByteArray,val client:ByteArray,val server:ByteArray):AutoCloseable {
        override fun close(){key.fill(0);client.fill(0);server.fill(0)}
    }
    fun respond(salt:ByteArray,serverWire:ByteArray,password:ByteArray):Proof {
        val legacy=dialect==PairSetupProof.EXPERIMENTAL_V11_PADDED
        require((if(legacy)salt.size in 16..64 else salt.size==16)&&
            (if(legacy)serverWire.size==384 else serverWire.size in 1..384)&&password.isNotEmpty())
        val b=BigInteger(1,serverWire);require(b.signum()>0&&b<n)
        val a=requireNotNull(privateValue)
        val k=BigInteger(1,Crypto.hash(min(n),pad(g)))
        val user="Pair-Setup".toByteArray(Charsets.US_ASCII)
        val x=BigInteger(1,Crypto.hash(salt,Crypto.hash(user,byteArrayOf(0x3a),password)))
        val u=BigInteger(1,Crypto.hash(pad(BigInteger(1,public)),pad(b)));require(u.signum()>0)
        val shared=b.subtract(k.multiply(g.modPow(x,n))).mod(n).modPow(a.add(u.multiply(x)),n)
        require(shared.signum()>0)
        val key=Crypto.hash(min(shared))
        val hn=Crypto.hash(min(n));val hg=Crypto.hash(min(g))
        val xor=ByteArray(64){(hn[it].toInt() xor hg[it].toInt()).toByte()}
        val proof=Crypto.hash(xor,Crypto.hash(user),salt,public,if(legacy)pad(b)else serverWire,key)
        return Proof(key,proof,Crypto.hash(public,proof,key))
    }
    override fun close(){privateValue=null}
    companion object {
        private val n=SRP6StandardGroups.rfc5054_3072.getN()
        private val g=SRP6StandardGroups.rfc5054_3072.g
        private fun min(x:BigInteger)=x.toByteArray().let{if(it.size>1&&it[0]==0.toByte())it.copyOfRange(1,it.size)else it}
        private fun pad(x:BigInteger)=min(x).let{require(it.size<=384);ByteArray(384-it.size)+it}
    }
}
