package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class LocationNmeaTest {
    private fun sentence(value:String):ByteArray {
        val bytes=value.toByteArray(Charsets.US_ASCII)
        val checksum=bytes.fold(0){a,b->a xor b.toInt()}
        return ("$"+value+"*"+checksum.toString(16).padStart(2,'0')).toByteArray()+byteArrayOf(0)
    }
    private fun parse(bytes:ByteArray)=LocationNmea.parse(0xfffb,IapFields(listOf(0 to bytes)))
    @Test fun confirmedTypesAreValidatedWithoutInferringPasCdUnits(){
        for(type in listOf("GPRMC","GPGGA","PASCD")){
            val bytes=sentence(type+",0,0")
            val parsed=parse(bytes)
            assertEquals(type,parsed.type)
            assertFalse(parsed.toString().contains(",0,0"))
            val copy=parsed.ascii();copy.fill(0)
            assertEquals('$'.code,parsed.ascii()[0].toInt())
        }
    }
    @Test fun badChecksumTerminatorExtraSentenceOrTlvIsRejected(){
        val good=sentence("GPRMC,0")
        val inputs=listOf(good.copyOf(good.size-1),good+byteArrayOf(0),good.copyOf().apply{this[2]=0},
            good.copyOf().apply{this[3]=0xff.toByte()},good.copyOf().apply{this[size-2]='Z'.code.toByte()},
            good.copyOf().apply{this[7]='1'.code.toByte()},good+good,
            good.copyOf(good.size-1)+byteArrayOf(13,10,0))
        for(bytes in inputs)assertThrows(IllegalArgumentException::class.java){parse(bytes)}
        assertThrows(IllegalArgumentException::class.java){LocationNmea.parse(0xfffb,IapFields(listOf(0 to good,0 to good)))}
        assertThrows(IllegalArgumentException::class.java){LocationNmea.parse(0xfffa,IapFields(listOf(0 to good)))}
    }
}
