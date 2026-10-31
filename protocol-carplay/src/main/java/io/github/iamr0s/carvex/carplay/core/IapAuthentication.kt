package io.github.iamr0s.carvex.carplay.core
import java.security.cert.X509Certificate

/** AA certificate request state belongs to one transport generation and link epoch (docs/03).
 * The legacy lingo signing API remains separate: only an actual MFi chip may provide that
 * hardware response, and no serial declaration or length hint establishes peer trust. */
object IapAuthentication {
    /** One AA/00 -> [AA/06 -> bare AA/00] -> AA/01 transaction, with no certificate cache.
     * Serial information is untrusted until a complete certificate and challenge are verified.
     * This class only enforces request/response association; it never establishes certificate trust. */
    class CertificateTransaction(private val generation:Int,private val linkEpoch:Long,
        accessorySends:Set<Int>?,timeoutMs:Long=20000,
        private val now:()->Long=System::nanoTime) {
        enum class State { IDLE, SERIAL_REQUESTED, CERT_REQUESTED, CERT_RECEIVED, FAILED }
        /** OPAQUE_HINT is never a certificate/cache identity or an authentication result. */
        enum class SerialBinding { NONE, OPAQUE_HINT, SERIAL_MATCHED }
        var state=State.IDLE
            private set
        var serialBinding=SerialBinding.NONE
            private set
        private val requestSerial=accessorySends?.contains(0xaa06)==true
        private val deadline:Long
        private var serial:ByteArray?=null
        init { require(timeoutMs in 1..120000);deadline=now()+timeoutMs*1_000_000 }
        /** Untrusted AA/06 bytes, including unsupported representations. Never a cache key.
         * A copy prevents later mutation from changing the current transaction's hint. */
        val serialIdentifier:ByteArray? get()=serial?.copyOf()
        private fun requireCurrent(generation:Int,linkEpoch:Long) {
            check(state!=State.FAILED)
            if(this.generation!=generation||this.linkEpoch!=linkEpoch||now()>=deadline){
                state=State.FAILED
                error("Expired or stale iAP authentication certificate transaction")
            }
        }
        private fun requireState(expected:State) {
            if(state!=expected){state=State.FAILED;error("Unexpected iAP authentication certificate response")}
        }
        fun requestFields(generation:Int,linkEpoch:Long):List<Pair<Int,ByteArray>> {
            requireCurrent(generation,linkEpoch);requireState(State.IDLE)
            state=if(requestSerial)State.SERIAL_REQUESTED else State.CERT_REQUESTED
            return if(requestSerial)listOf(0 to byteArrayOf())else emptyList()
        }
        /** Returns the single permitted bare AA/00 request. The observed 16-byte AA/06
         * fast path does not establish its mapping to a certificate or a trusted cache.
         * All hints take this cold-cache path; only an evidenced representation can be
         * checked against the subsequently received complete certificate. */
        fun onSerial(fields:IapFields,generation:Int,linkEpoch:Long):List<Pair<Int,ByteArray>> {
            requireCurrent(generation,linkEpoch);requireState(State.SERIAL_REQUESTED)
            val field=fields.ordered.singleOrNull()
            if(field==null||field.first!=0||field.second.size !in 1..256){
                state=State.FAILED;error("Invalid iAP authentication serial container")
            }
            serial=field.second.copyOf();serialBinding=SerialBinding.OPAQUE_HINT
            state=State.CERT_REQUESTED
            return emptyList()
        }
        fun acceptCertificate(cert:X509Certificate,schema:AccessoryAuth.ChallengeSchema,
            generation:Int,linkEpoch:Long) {
            requireCurrent(generation,linkEpoch);requireState(State.CERT_REQUESTED)
            val hint=serial
            // A 32-byte candidate belongs to the evidenced P-256 ASCII representation.
            // Keep malformed case/hex and an appended NUL fail-closed rather than allowing
            // a malformed known representation to escape validation as an opaque hint.
            // Other lengths (notably the observed binary16) have no established serial
            // mapping. They cannot select an algorithm or certify/cache this new key.
            val checkP256Serial=hint!=null&&schema==AccessoryAuth.ChallengeSchema.AA_P256_SHA256_DIGEST_V1&&
                (hint.size==32||(hint.size==33&&hint.last()==0.toByte()))
            if(checkP256Serial&&!AccessoryAuth.matchesP256Serial(cert.serialNumber,checkNotNull(hint))){
                state=State.FAILED;error("iAP authentication serial does not match certificate")
            }
            if(checkP256Serial)serialBinding=SerialBinding.SERIAL_MATCHED
            state=State.CERT_RECEIVED
        }
    }
    /** An AA/06 declaration in IdentifyInfo is evidence for the optional empty pid0 request.
     * A missing IdentifyInfo (null) is distinct from a known empty capability list. */
    fun certificateRequestFields(accessorySends:Set<Int>?):List<Pair<Int,ByteArray>> =
        if(accessorySends?.contains(0xaa06)==true)listOf(0 to byteArrayOf())else emptyList()

    /** U2IW's AA/06 length classification is diagnostic evidence only. It must not select a
     * verifier or replace certificate/signature validation. Restrict it to the known pid0 shape. */
    fun u2iwModernAuthHint(fields:IapFields):Boolean {
        if(fields.ordered.size!=1||fields.ordered.single().first!=0)return false
        val serial=fields.ordered.single().second
        return serial.size==16||(serial.size==32&&(serial[0]==0x34.toByte()||serial[0]==0x35.toByte()))
    }

    /** MFi 认证芯片：签名由它完成，软件无法替代。 */
    fun interface Chip { fun sign(challenge:ByteArray):ByteArray }

    enum class Action {
        /** 对端索取客户端认证信息 —— 只有 MFi 芯片能给出签名。 */
        SIGN,
        /** 对端在通报它自己的认证信息或签名，客户端接收。 */
        ACCEPT,
        /** 本固件之外或未确认的命令：记录后继续，不阻断链路。 */
        RECORD
    }

    /** 没有芯片时明确失败。返回空签名或伪造成功会让失败在对端才显现，并且归因错误。 */
    fun sign(chip:Chip?,challenge:ByteArray):ByteArray=
        (chip?:throw UnsupportedOperationException("authentication signature needs an MFi auth chip; this build has no such hardware (spec 2.3, unavoidable)"))
            .sign(challenge)

    fun action(lingo:Int,command:Int):Action = when {
        lingo!=0 -> Action.RECORD
        command==0x1a -> Action.SIGN
        command==0x15 -> Action.ACCEPT
        command==0x18 -> Action.ACCEPT
        else -> Action.RECORD
    }
}
