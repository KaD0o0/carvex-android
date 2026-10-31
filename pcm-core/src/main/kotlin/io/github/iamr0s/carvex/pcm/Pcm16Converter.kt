package io.github.iamr0s.carvex.pcm

/** Stateful PCM16LE linear interpolation. Keeps phase across Binder blocks; never mixes categories.
 * Not a band-limited mastering resampler. Recreate on source-format changes or discontinuities.
 * Frame buffers and the output buffer are reused, so a steady stream allocates only its result. */
class Pcm16Converter(val source:PcmFormat,val target:PcmFormat) {
    private var index=-1L
    private var position=0L
    private val previous=IntArray(target.channels)
    private val current=IntArray(target.channels)
    private var buffer=ByteArray(maxOf(2,target.rate/50)*target.channels*2)
    private var written=0
    private fun read(input:ByteArray,at:Int)=((input[at].toInt() and 255) or (input[at+1].toInt() shl 8)).toShort().toInt()
    private fun ensure(extra:Int){
        val needed=written+extra
        if(needed>buffer.size)buffer=buffer.copyOf(maxOf(buffer.size*2,needed))
    }
    private fun put(value:Int){
        buffer[written]=(value and 255).toByte();buffer[written+1]=((value shr 8) and 255).toByte();written+=2
    }
    private fun writeFrame(values:IntArray){
        ensure(target.channels*2)
        for(channel in 0 until target.channels)put(values[channel])
    }
    fun convert(input:ByteArray):ByteArray {
        require(source.accepts(input.size))
        if(source==target)return input.copyOf()
        written=0
        for(offset in input.indices step source.channels*2){
            when{
                source.channels==target.channels->for(channel in 0 until target.channels)current[channel]=read(input,offset+channel*2)
                target.channels==1->current[0]=(read(input,offset)+read(input,offset+2))/2
                else->{current[0]=read(input,offset);current[1]=current[0]}
            }
            index++
            if(index==0L){writeFrame(current);position+=source.rate}
            else while(position<=index*target.rate){
                val fraction=position-(index-1)*target.rate
                ensure(target.channels*2)
                for(channel in 0 until target.channels){
                    put((previous[channel]+(current[channel]-previous[channel])*fraction/target.rate).toInt().coerceIn(-32768,32767))
                }
                position+=source.rate
            }
            System.arraycopy(current,0,previous,0,target.channels)
        }
        return buffer.copyOf(written)
    }
}
