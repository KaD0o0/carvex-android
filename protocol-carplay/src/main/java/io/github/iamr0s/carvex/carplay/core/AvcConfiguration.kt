package io.github.iamr0s.carvex.carplay.core

/** Accumulates parameter sets across output-format CSD and codec-config buffers. */
class AvcConfiguration {
    private var sps:ByteArray?=null
    private var pps:ByteArray?=null
    private var sent:ByteArray?=null
    private var dirty=false
    fun ingest(data:ByteArray){
        if(data.isEmpty())return
        // Some codecs supply a single bare parameter-set NAL in a CSD buffer.
        val type=data[0].toInt() and 31
        val units=if(type==7||type==8)listOf(data) else VideoWire.splitNal(data)
        ingestUnits(units)
    }
    fun ingestUnits(units:List<ByteArray>){
        for(unit in units){
            require(unit.isNotEmpty())
            when(unit[0].toInt() and 31){
                7->{require(unit.size in 4..65535);if(sps?.contentEquals(unit)!=true){sps=unit.copyOf();dirty=true}}
                8->{require(unit.size in 1..65535);if(pps?.contentEquals(unit)!=true){pps=unit.copyOf();dirty=true}}
            }
        }
    }
    fun takeChanged():ByteArray?{
        if(!dirty)return null
        val a=sps?:return null;val b=pps?:return null
        val config=VideoWire.avcc(a,b)
        dirty=false
        if(sent?.contentEquals(config)==true)return null
        sent=config.copyOf();return config
    }
    fun ready()=sent!=null
    /** Recovery reuses only the configuration emitted for this encoder instance. */
    fun current():ByteArray?=sent?.copyOf()
}
