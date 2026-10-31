package io.github.iamr0s.carvex.carplay.core

/** LOCATION_NMEA_CSM_V1 decoder only. No automatic subscription or vehicle-state interpretation. */
object LocationNmea {
    const val INFORMATION = 0xfffb
    class Sentence internal constructor(private val raw:ByteArray,val type:String) {
        fun ascii():ByteArray=raw.copyOf()
        override fun toString()="NMEA(type="+type+", bytes="+raw.size+")"
    }
    fun parse(messageId:Int,fields:IapFields,maxSentenceBytes:Int=1024):Sentence {
        require(messageId==INFORMATION&&maxSentenceBytes in 8..4096)
        val field=fields.ordered.singleOrNull();require(field?.first==0)
        val bytes=requireNotNull(field).second
        require(bytes.size in 8..maxSentenceBytes&&bytes.last()==0.toByte())
        val body=bytes.copyOf(bytes.size-1)
        require(body.all{it.toInt() in 0x20..0x7e})
        require(body.first()==0x24.toByte())
        val star=body.indexOf(0x2a.toByte());require(star>=2&&star==body.size-3)
        fun hex(byte:Byte):Int=byte.toInt().toChar().digitToIntOrNull(16)?:throw IllegalArgumentException()
        val expected=(hex(body[star+1]) shl 4) or hex(body[star+2])
        var actual=0
        for(index in 1 until star)actual=actual xor body[index].toInt()
        require(actual==expected)
        val comma=body.indexOf(0x2c.toByte()).takeIf{it in 2 until star}?:star
        val type=body.copyOfRange(1,comma).toString(Charsets.US_ASCII)
        require(type.length in 3..8&&type.all{it in 'A'..'Z'||it in '0'..'9'})
        return Sentence(body,type)
    }
}
