package io.github.iamr0s.carvex.carplay.core
/** Fields of one HID usage page, read out of the descriptor. Consumer Control (0x0C) carries the
 *  media keys and Keyboard (0x07) the navigation keys; both are decoded the same way, so the page is
 *  a parameter instead of a second parser. */
class HidUsages private constructor(private val fields:List<Field>,private val lengths:Map<Int,Int>,private val page:Int) {
    private data class Field(val id:Int,val offset:Int,val size:Int,val count:Int,val usages:List<Int>,val array:Boolean,val min:Int,val max:Int)
    private val reports=mutableMapOf<Int,Set<Int>>()
    @Synchronized fun decode(report:ByteArray):Set<Int>{
        require(report.isNotEmpty())
        val numbered=lengths.keys.any{it!=0}
        val id=if(numbered)report[0].toInt() and 255 else 0
        val start=if(numbered)1 else 0
        require(report.size==start+((requireNotNull(lengths[id])+7)/8)){"Invalid HID report length or ID"}
        val active=linkedSetOf<Int>()
        for(f in fields.filter{it.id==id})for(i in 0 until f.count){
            var value=0
            for(bit in 0 until f.size){val at=start*8+f.offset+i*f.size+bit;value=value or (((report[at/8].toInt() ushr (at%8)) and 1) shl bit)}
            if(f.array){
                if(value in f.min..f.max)f.usages.getOrNull(value-f.min)?.takeIf{it!=0}?.let{active.add(it)}
            }else if(value!=0&&value in f.min..f.max)f.usages.getOrNull(i)?.takeIf{it!=0}?.let{active.add(it)}
        }
        reports[id]=active
        return reports.values.flatten().toSet()
    }
    companion object {
        const val PAGE_CONSUMER=0x0c
        const val PAGE_KEYBOARD=0x07
        /** 描述符里声明过的 usage page（`05 xx` 项）。用来对每个页各解析一遍，而不是只试固定两页 ——
         *  厂商设备会把按键放在 Telephony（0x0B）等其它页上。 */
        fun pages(desc:ByteArray):Set<Int> {
            // 固件对 hidDescriptor 只受 iAP2 字段长度限制（65535），没有 4 KB 上限。
            require(desc.size in 1..65535)
            val result=linkedSetOf<Int>();var i=0
            while(i<desc.size){
                val prefix=desc[i++].toInt() and 255
                if(prefix==0xfe){
                    // Long item：0xFE, 数据长度, tag, 数据……跳过。
                    require(i+2<=desc.size)
                    val size=desc[i].toInt() and 255
                    i+=2;require(size<=desc.size-i);i+=size;continue
                }
                val n=when(prefix and 3){0->0;1->1;2->2;else->4}
                require(n<=desc.size-i)
                var value=0;for(k in 0 until n)value=value or ((desc[i+k].toInt() and 255) shl (8*k))
                i+=n
                if(prefix and 0xfc==0x04)result.add(value)
                // A 32-bit local Usage carries its own page, even when no matching
                // global Usage Page item precedes it.
                if(n==4&&(prefix and 0xfc) in setOf(0x08,0x18,0x28))result.add(value ushr 16)
            }
            return result
        }
        private data class Global(var page:Int=0,var size:Int=0,var count:Int=0,var id:Int=0,var min:Int=0,var max:Int=0)
        fun parse(desc:ByteArray,page:Int=PAGE_CONSUMER):HidUsages{
            require(desc.size in 1..65535)
            var g=Global();val stack=ArrayDeque<Global>()
            val usages=mutableListOf<Int>();var usageMin:Int?=null
            val fields=mutableListOf<Field>();val lengths=mutableMapOf<Int,Int>()
            var i=0;var depth=0
            fun usage(value:Int,n:Int)=if(n==4)value else (g.page shl 16) or value
            while(i<desc.size){
                val prefix=desc[i++].toInt() and 255
                require(prefix!=0xfe){"Long HID items unsupported"}
                val n=when(prefix and 3){0->0;1->1;2->2;else->4};require(i+n<=desc.size)
                var value=0;for(k in 0 until n)value=value or ((desc[i+k].toInt() and 255) shl (8*k));i+=n
                fun signed()=if(n in 1..3)(value shl (32-n*8)) shr (32-n*8) else value
                when(prefix and 0xfc){
                    0x04->g.page=value
                    0x14->g.min=signed()
                    0x24->g.max=if(g.min<0)signed() else value
                    0x74->g.size=value
                    0x94->g.count=value
                    0x84->{require(value in 1..255);g.id=value}
                    0xa4->{require(stack.size<16);stack.addLast(g.copy())}
                    0xb4->{require(stack.isNotEmpty());g=stack.removeLast()}
                    0x08->{require(usages.size<512);usages.add(usage(value,n))}
                    0x18->usageMin=usage(value,n)
                    0x28->{val lo=requireNotNull(usageMin);val hi=usage(value,n);require(hi>=lo&&hi.toLong()-lo<512&&hi.toLong()-lo+1<=512-usages.size);usages.addAll(lo..hi)}
                    0xa0->{require(++depth<=32);usages.clear();usageMin=null}
                    0xc0->{require(--depth>=0);usages.clear();usageMin=null}
                    0x80->{
                        require(g.size in 1..32&&g.count in 1..512)
                        val offset=lengths[g.id]?:0;require(offset+g.size*g.count<=8192)
                        // Keep the selector/bit position of usages from other pages. Filtering
                        // them out shifts later usages and can turn a different key into NEXT.
                        val wanted=usages.map{if(it ushr 16==page)it and 65535 else 0}
                        if(value and 1==0&&wanted.any{it!=0}){
                            require(g.size<=16&&g.min>=0&&g.max>=g.min){"Unsupported field for usage page 0x"+page.toString(16)}
                            require(value and 4==0){"Relative controls unsupported"}
                            fields.add(Field(g.id,offset,g.size,g.count,wanted,value and 2==0,g.min,g.max))
                        }
                        lengths[g.id]=offset+g.size*g.count;usages.clear();usageMin=null
                    }
                    0x90,0xb0->{usages.clear();usageMin=null}
                    0xa8->throw IllegalArgumentException("HID usage delimiter unsupported")
                }
            }
            require(depth==0&&stack.isEmpty()&&fields.isNotEmpty()){"No unambiguous fields for usage page 0x"+page.toString(16)}
            require(!(0 in lengths&&lengths.keys.any{it!=0})){"Mixed numbered and unnumbered reports"}
            return HidUsages(fields,lengths,page)
        }
    }
}
