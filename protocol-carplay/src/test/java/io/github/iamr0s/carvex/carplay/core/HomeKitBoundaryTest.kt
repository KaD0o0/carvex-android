package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class HomeKitBoundaryTest {
    private val identity=PairIdentity("synthetic-controller",ByteArray(32){it.toByte()})

    @Test fun setupRequiresAnExplicitCodeBeforeWritingAnyRequest(){
        val output=ByteArrayOutputStream()
        val homeKit=HomeKit(RtspClient(ByteArrayInputStream(byteArrayOf()),output),identity)
        assertThrows(PairUnsupported::class.java){homeKit.setup()}
        assertEquals(0,output.size())
    }

    @Test fun setupUsesMethodThenStateAndTheSetupHkpHeader(){
        val input=response(1,Tlv8.encode(Tlv8.state(2),2 to ByteArray(15),3 to ByteArray(384)))
        val output=ByteArrayOutputStream()
        val homeKit=HomeKit(RtspClient(ByteArrayInputStream(input),output),identity)
        assertThrows(IllegalArgumentException::class.java){homeKit.setup(setupCode="synthetic-code".toByteArray())}
        val request=RtspWire.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals("0",request.headers["x-apple-hkp"])
        assertArrayEquals(byteArrayOf(0,1,0,6,1,1),request.body)
    }

    @Test fun verifyInstallsRecordKeysOnlyAfterSignedM2AndSuccessfulM4(){
        val exchange=VerifyExchange()
        val result=HomeKit(exchange.client,identity).verify(exchange.peer)
        assertArrayEquals(exchange.shared,result)
        assertTrue(exchange.client.input is RecordInput)
        assertTrue(exchange.client.output is RecordOutput)
        assertTrue(exchange.controllerSignatureVerified)
    }

    @Test fun v11CompatibilityCanSendEncryptedSetupAfterSignedVerify(){
        val exchange=VerifyExchange()
        val homeKit=HomeKit(exchange.client,identity,ExperimentalHapV11::srp)
        var saved=false
        ExperimentalHapV11.authenticate(exchange.peer.identifier,exchange.peer.public,exchange.peer,
            setup={fail("stored peer must verify directly");exchange.peer},verify=homeKit::verify,
            saveVerified={assertTrue(exchange.controllerSignatureVerified);saved=true})
        assertTrue(saved)
        exchange.client.plist("SETUP","/setup",mapOf("type" to 110))
        assertTrue(exchange.encryptedSetupReceived)
    }

    @Test fun m4ErrorNeverInstallsEncryptedControl(){
        val exchange=VerifyExchange(failM4=true)
        assertThrows(IllegalArgumentException::class.java){HomeKit(exchange.client,identity).verify(exchange.peer)}
        assertFalse(exchange.client.input is RecordInput)
        assertFalse(exchange.client.output is RecordOutput)
    }

    @Test fun decodedStringAliasCannotReplaceTheExactPeerIdentifier(){
        val original=byteArrayOf(0xc0.toByte())
        val received=byteArrayOf(0xc1.toByte())
        assertEquals(original.toString(Charsets.UTF_8),received.toString(Charsets.UTF_8))
        val exchange=VerifyExchange(original,received)
        assertThrows(IllegalArgumentException::class.java){HomeKit(exchange.client,identity).verify(exchange.peer)}
        assertFalse(exchange.client.input is RecordInput)
    }

    private inner class VerifyExchange(storedId:ByteArray="synthetic-receiver".toByteArray(),
        private val sentId:ByteArray=storedId,private val failM4:Boolean=false){
        private val seed=ByteArray(32){(it+41).toByte()}
        private val ephemeral=ByteArray(32){(it+71).toByte()}
        private val public=Crypto.xPublic(ephemeral)
        val peer=PeerIdentity(storedId.toString(Charsets.UTF_8),Crypto.public(seed),storedId)
        var shared=byteArrayOf()
        var controllerSignatureVerified=false
        var encryptedSetupReceived=false
        private var verified=false
        private var encryptionKey=byteArrayOf()
        private var localPublic=byteArrayOf()
        private var pending=ByteArrayInputStream(byteArrayOf())
        private val input=object:InputStream(){
            override fun read()=pending.read()
            override fun read(b:ByteArray,off:Int,len:Int)=pending.read(b,off,len)
        }
        private val output=object:ByteArrayOutputStream(){override fun flush(){
            val wire=toByteArray();reset()
            if(verified){
                val request=RtspWire.read(RecordInput(ByteArrayInputStream(wire),Crypto.hkdf(shared,"Control-Salt","Control-Write-Encryption-Key")))
                assertEquals("SETUP /setup RTSP/1.0",request.first)
                assertEquals(110,(Plist.dictionary(request.body)["type"] as Number).toInt())
                val reply=ByteArrayOutputStream()
                RecordOutput(reply,Crypto.hkdf(shared,"Control-Salt","Control-Read-Encryption-Key")).also{
                    RtspWire.write(it,"RTSP/1.0 200 OK",mapOf("CSeq" to request.headers["cseq"]!!),Plist.encode(emptyMap<String,Any>()))
                }
                pending=ByteArrayInputStream(reply.toByteArray())
                encryptedSetupReceived=true
                return
            }
            val request=RtspWire.read(ByteArrayInputStream(wire))
            assertEquals("2",request.headers["x-apple-hkp"])
            assertEquals("1",request.headers["x-apple-pd"])
            val fields=Tlv8.decode(request.body)
            val seq=request.headers["cseq"]!!.toInt()
            val body=if(fields[6]!!.contentEquals(byteArrayOf(1))){
                localPublic=fields[3]!!
                shared=Crypto.shared(ephemeral,localPublic)
                encryptionKey=Crypto.hkdf(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info")
                val inner=Tlv8.encode(1 to sentId,10 to Crypto.sign(seed,public+sentId+localPublic))
                Tlv8.encode(Tlv8.state(2),3 to public,5 to Crypto.seal(encryptionKey,Crypto.fixedNonce("PV-Msg02"),inner))
            }else{
                val inner=Tlv8.decode(Crypto.open(encryptionKey,Crypto.fixedNonce("PV-Msg03"),fields[5]!!))
                val id=identity.id.toByteArray()
                controllerSignatureVerified=Crypto.verify(Crypto.public(identity.seed),localPublic+id+public,inner[10]!!)
                if(failM4)Tlv8.encode(Tlv8.state(4),7 to byteArrayOf(2))else {verified=true;Tlv8.encode(Tlv8.state(4))}
            }
            pending=ByteArrayInputStream(response(seq,body))
        }}
        val client=RtspClient(input,output)
    }

    private fun response(seq:Int,body:ByteArray)=ByteArrayOutputStream().also{
        RtspWire.write(it,"RTSP/1.0 200 OK",mapOf("CSeq" to seq.toString()),body)
    }.toByteArray()
}
