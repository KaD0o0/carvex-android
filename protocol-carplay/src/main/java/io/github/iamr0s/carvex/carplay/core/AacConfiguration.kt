package io.github.iamr0s.carvex.carplay.core
/** MPEG-4 AudioSpecificConfig subset: LC and ELD without SBR/resilience extensions.
 * Codec data only; never serialized as a CarPlay SETUP structure. */
object AacConfiguration {
    data class Description(val objectType:Int,val rate:Int,val channels:Int,val frames:Int)
    private val rates=intArrayOf(96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350)
    private class Reader(private val bytes:ByteArray){
        private var offset=0
        fun read(count:Int):Int {
            require(count in 0..24&&offset+count<=bytes.size*8){"Truncated AudioSpecificConfig"}
            var result=0
            repeat(count){result=(result shl 1) or ((bytes[offset/8].toInt() ushr (7-offset%8)) and 1);offset++}
            return result
        }
    }
    fun parse(bytes:ByteArray):Description {
        val r=Reader(bytes);val first=r.read(5);val type=if(first==31)32+r.read(6) else first
        val index=r.read(4);val rate=if(index==15)r.read(24) else rates.getOrNull(index)?:error("Invalid AAC rate index")
        val channels=r.read(4);require(channels in 1..2){"AAC channel/PCE configuration unsupported"}
        val short=r.read(1)!=0
        val frames=when(type){
            2->{require(r.read(1)==0&&r.read(1)==0){"AAC LC core/extension configuration unsupported"};if(short)960 else 1024}
            39->{
                require(r.read(3)==0&&r.read(1)==0){"ELD resilience/SBR configuration unsupported"}
                require(r.read(4)==0&&r.read(2)==0){"ELD extension/error protection unsupported"}
                if(short)480 else 512
            }
            else->error("${"Unsupported AAC object type="}${type}")
        }
        return Description(type,rate,channels,frames)
    }
    fun validate(bytes:ByteArray,format:CarPlayAudioPlan.Format):Description = parse(bytes).also{actual->
        val type=when(format.codec){CarPlayAudioPlan.Codec.AAC_LC->2;CarPlayAudioPlan.Codec.AAC_ELD->39;else->error("Not AAC")}
        require(actual.objectType==type&&actual.rate==format.rate&&actual.channels==format.channels&&actual.frames==format.framesPerPacket){"${"AAC configuration differs from announced audioFormat: "}${actual}"}
    }
    fun decoderConfig(format:CarPlayAudioPlan.Format):ByteArray {
        val type=when(format.codec){CarPlayAudioPlan.Codec.AAC_LC->2;CarPlayAudioPlan.Codec.AAC_ELD->39;else->error("Not AAC")}
        val index=rates.indexOf(format.rate);require(index>=0)
        val bits=mutableListOf<Int>()
        fun put(value:Int,count:Int){for(shift in count-1 downTo 0)bits.add((value ushr shift) and 1)}
        if(type<31)put(type,5) else{put(31,5);put(type-32,6)}
        put(index,4);put(format.channels,4)
        if(type==2){require(format.framesPerPacket in setOf(960,1024));put(if(format.framesPerPacket==960)1 else 0,1);put(0,2)}
        else{require(format.framesPerPacket in setOf(480,512));put(if(format.framesPerPacket==480)1 else 0,1);put(0,3);put(0,1);put(0,4);put(0,2)}
        return ByteArray((bits.size+7)/8).also{bytes->for(i in bits.indices)bytes[i/8]=(bytes[i/8].toInt() or (bits[i] shl (7-i%8))).toByte()}
    }
}
