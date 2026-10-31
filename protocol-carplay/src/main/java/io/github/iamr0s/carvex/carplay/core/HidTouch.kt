package io.github.iamr0s.carvex.carplay.core
/** Supports descriptor-declared absolute contacts; exposes the first active contact to the host adapter. */
data class HidField(val offset:Int,val size:Int,val min:Int,val max:Int)
data class TouchSample(val pressed:Boolean,val x:Float,val y:Float)
class HidTouch private constructor(private val id:Int,private val bits:Int,
    private val tips:List<HidField>,private val xs:List<HidField>,private val ys:List<HidField>) {
    private var primary:Int?=null
    private var lastX=0f;private var lastY=0f
    /** Check routing without changing the active contact or the last delivered coordinates. */
    fun matchesReport(report:ByteArray):Boolean {
        val start=if(id==0)0 else 1
        return report.size==(bits+7)/8+start && (id==0||(report[0].toInt() and 255)==id)
    }
    @Synchronized fun decode(report:ByteArray,w:Int,h:Int):TouchSample {
        require(w>0&&h>0)
        require(matchesReport(report))
        val start=if(id==0)0 else 1
        fun value(f:HidField):Int {
            var n=0;repeat(f.size){i->val bit=f.offset+i;n=n or (((report[start+bit/8].toInt() ushr (bit%8)) and 1) shl i)}
            if(f.min<0&&n and (1 shl (f.size-1))!=0)n=n or (-1 shl f.size)
            return n
        }
        fun coord(f:HidField,limit:Int)=((value(f).coerceIn(f.min,f.max)-f.min).toFloat()/(f.max-f.min))*(limit-1)
        require(tips.size==xs.size&&tips.size==ys.size&&tips.isNotEmpty())
        val held=primary
        if(held!=null&&value(tips[held])==0){
            primary=null
            return TouchSample(false,lastX,lastY)
        }
        val active=held?:tips.indices.firstOrNull{value(tips[it])!=0}
        if(active==null)return TouchSample(false,lastX,lastY)
        primary=active;lastX=coord(xs[active],w);lastY=coord(ys[active],h)
        return TouchSample(true,lastX,lastY)
    }
    companion object {
        /** 上一次 parse 看到的候选绝对触摸报告数量。>1 表示描述符里有多份，取的是报告 id 最小的那份；
         *  调用方可以据此记录"描述符不唯一"（宿主侧策略，第 10 章标为本地收紧）。 */
        @Volatile var ambiguousReportCount:Int=0
        fun parse(b:ByteArray):HidTouch {
            // 固件对 hidDescriptor 只受 iAP2 字段长度限制（65535），没有 4 KB 上限。
            require(b.size in 1..65535)
            data class Globals(var page:Int=0,var min:Int=0,var max:Int=0,var size:Int=0,var count:Int=0,var id:Int=0)
            var g=Globals();val stack=ArrayDeque<Globals>();var usages=mutableListOf<Int>()
            var usageMin:Int?=null;var usageMax:Int?=null
            data class CandidateField(val field:HidField,val flags:Long)
            val offsets=mutableMapOf<Int,Int>()
            val fields=mutableMapOf<Int,MutableMap<Int,MutableMap<Int,MutableList<CandidateField>>>>()
            val collections=ArrayDeque<Int>();var contactGroup=0;var nextGroup=0
            var p=0
            while(p<b.size){
                val prefix=b[p++].toInt() and 255;require(prefix!=254){"Long HID items unsupported"}
                val size=when(prefix and 3){3->4;else->prefix and 3};require(p+size<=b.size)
                var unsigned=0L;repeat(size){unsigned=unsigned or ((b[p+it].toLong() and 255) shl (8*it))}
                val signed=if(size>0 && unsigned and (1L shl (size*8-1))!=0L)(unsigned or (-1L shl (size*8))).toInt()else unsigned.toInt()
                p+=size;val type=(prefix ushr 2) and 3;val tag=prefix ushr 4
                when(type){
                    1->when(tag){0->g.page=unsigned.toInt();1->g.min=signed;2->g.max=if(g.min<0)signed else unsigned.toInt();7->g.size=unsigned.toInt();8->{g.id=unsigned.toInt();require(g.id in 1..255)};9->g.count=unsigned.toInt();10->{require(stack.size<16);stack.addLast(g.copy())};11->{require(stack.isNotEmpty());g=stack.removeLast()}}
                    2->when(tag){0->usages.add(if(size==4)unsigned.toInt()else(g.page shl 16) or unsigned.toInt());1->usageMin=unsigned.toInt();2->usageMax=unsigned.toInt()}
                    0->{
                        if(tag==8) {
                            require(g.size in 1..32 && g.count in 1..256)
                            val off=offsets[g.id]?:0;require(off+g.size*g.count<=4096)
                            if(unsigned and 1L==0L){
                                if(usages.isEmpty()&&usageMin!=null&&usageMax!=null){
                                    require(usageMax!!>=usageMin!! && usageMax!!.toLong()-usageMin!!<256)
                                    usages=(usageMin!!..usageMax!!).map{(g.page shl 16) or it}.toMutableList()
                                }
                                repeat(g.count){i->
                                    val usage=usages.getOrNull(i)?:usages.lastOrNull()
                                    if(usage in listOf(0x0d0033,0x0d0042,0x010030,0x010031)){
                                        // Other reports can contain relative axes or keyboard arrays.
                                        // Validate only fields belonging to the selected contact report.
                                        val key=if(usage==0x0d0033)0x0d0042 else usage!!
                                        val map=fields.getOrPut(g.id){mutableMapOf()}.getOrPut(contactGroup){mutableMapOf()}
                                        map.getOrPut(key){mutableListOf()}.add(CandidateField(
                                            HidField(off+i*g.size,g.size,g.min,g.max),unsigned))
                                    }
                                }
                            };offsets[g.id]=off+g.size*g.count
                        }
                        if(tag==10){
                            require(collections.size<32){"HID collection depth exceeds local parser budget"}
                            collections.addLast(contactGroup)
                            if(usages.lastOrNull()==0x0d0022)contactGroup=++nextGroup
                        }else if(tag==12){
                            require(collections.isNotEmpty()){"Unbalanced HID collection"}
                            contactGroup=collections.removeLast()
                        }
                        usages.clear();usageMin=null;usageMax=null
                    }
                }
            }
            require(collections.isEmpty()){"Unbalanced HID collection"}
            val keys=setOf(0x0d0042,0x010030,0x010031)
            // Axes without a contact flag belong to another input function, such as a mouse.
            val contactReports=fields.mapValues{(_,groups)->groups.filterValues{0x0d0042 in it}}
            val candidates=contactReports.filterValues{groups->groups.isNotEmpty()&&groups.values.all{it.keys.containsAll(keys)}}
            // 多个候选报告（例如触摸屏与笔共存）时取报告 id 最小的那个，并把"不唯一"记下来供调用方记录。
            // 这是宿主侧策略：第 10 章把"候选不唯一即整体禁用"列为本地收紧，不是协议约束。
            require(candidates.isNotEmpty()){"No unique complete absolute touch report"}
            ambiguousReportCount=candidates.size
            val (id,groups)=candidates.entries.minByOrNull{it.key}!!
            val contacts=groups.values.map{group->group.mapValues{(key,candidates)->
                require(candidates.size==1){"Duplicate or ambiguous field in HID contact collection"}
                val candidate=candidates.single();val field=candidate.field
                require(candidate.flags and 2L!=0L){"HID arrays unsupported"}
                require(candidate.flags and 4L==0L){"Relative HID unsupported"}
                require(field.size<=24&&field.max>field.min)
                if(key==0x0d0042)require(field.size==1&&field.min==0&&field.max==1){"Unsupported HID contact flag"}
                field
            }}
            return HidTouch(id,offsets.getValue(id),contacts.map{it.getValue(0x0d0042)},contacts.map{it.getValue(0x010030)},contacts.map{it.getValue(0x010031)})
        }
    }
}
