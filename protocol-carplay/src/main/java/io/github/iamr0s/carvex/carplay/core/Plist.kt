package io.github.iamr0s.carvex.carplay.core
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Strict bounded subset of binary plist. */
object Plist {
    private data class Node(val value: Any?, val refs: List<Int> = emptyList())
    private val MAGIC="bplist00".toByteArray(Charsets.US_ASCII)
    fun encode(value: Any?): ByteArray {
        val nodes=mutableListOf<Node>()
        fun add(v:Any?):Int {
            val refs=when(v) {
                is Map<*,*> -> v.keys.map { require(it is String); add(it) } + v.values.map { add(it) }
                is List<*> -> v.map { add(it) }
                else -> emptyList()
            }
            val i=nodes.size; nodes+=Node(v,refs); return i
        }
        val root=add(value); require(nodes.size<65536)
        val refSize=if(nodes.size<256) 1 else 2
        fun bytes(n:Long,size:Int)=ByteArray(size) { (n ushr (8*(size-1-it))).toByte() }
        fun length(tag:Int,n:Int):ByteArray = if(n<15) byteArrayOf((tag or n).toByte())
            else byteArrayOf((tag or 15).toByte(),0x12)+bytes(n.toLong(),4)
        val out=ByteArrayOutputStream(); out.write("bplist00".toByteArray())
        val offsets=nodes.map { node ->
            val at=out.size().toLong()
            when(val v=node.value) {
                null -> out.write(0)
                is Boolean -> out.write(if(v)9 else 8)
                is Number -> if(v is Double || v is Float) { out.write(0x23); out.write(bytes(java.lang.Double.doubleToRawLongBits(v.toDouble()),8)) }
                    else { out.write(0x13); out.write(bytes(v.toLong(),8)) }
                is ByteArray -> { out.write(length(0x40,v.size)); out.write(v) }
                is String -> {
                    val ascii=v.all { it.code<128 }
                    out.write(length(if(ascii)0x50 else 0x60,v.length)); out.write(v.toByteArray(if(ascii)Charsets.US_ASCII else Charsets.UTF_16BE))
                }
                is List<*> -> { out.write(length(0xa0,v.size)); node.refs.forEach { out.write(bytes(it.toLong(),refSize)) } }
                is Map<*,*> -> { out.write(length(0xd0,v.size)); node.refs.forEach { out.write(bytes(it.toLong(),refSize)) } }
                else -> error("Unsupported plist value")
            }; at
        }
        val table=out.size().toLong(); offsets.forEach { out.write(bytes(it,4)) }
        out.write(ByteArray(6)); out.write(4); out.write(refSize); out.write(bytes(nodes.size.toLong(),8)); out.write(bytes(root.toLong(),8)); out.write(bytes(table,8))
        return out.toByteArray()
    }
    fun decode(b:ByteArray): Any? {
        require(b.size in 40..4*1024*1024 && b.copyOfRange(0,8).contentEquals("bplist00".toByteArray())) { "Not binary plist" }
        val end=b.size-32
        fun uint(p:Int,n:Int):Long {
            require(n in 1..8 && p>=0 && p+n<=b.size)
            var v=0L; repeat(n){ v=(v shl 8) or (b[p+it].toLong() and 255) }; return v
        }
        val os=b[end+6].toInt() and 255; val rs=b[end+7].toInt() and 255
        require(os in 1..8 && rs in 1..8)
        val count=uint(end+8,8); val root=uint(end+16,8); val table=uint(end+24,8)
        require(count in 1..65535 && root in 0 until count && table>=8 && table+count*os<=end)
        val offsets=LongArray(count.toInt()) { uint(table.toInt()+it*os,os).also { o-> require(o in 8 until table) } }
        val visiting=HashSet<Int>(); val cache=HashMap<Int,Any?>(); var total=0;var allocated=0L
        fun read(idx:Int,depth:Int):Any? {
            require(idx in offsets.indices && depth<64); if(cache.containsKey(idx))return cache[idx]
            require(visiting.add(idx)) { "Cyclic plist" }; require(++total<=65535)
            var p=offsets[idx].toInt()
            fun take(n:Int):ByteArray { allocated+=n;require(allocated<=8L*1024*1024);require(n>=0 && p.toLong()+n<=table); return b.copyOfRange(p,p+n).also {p+=n} }
            val tag=take(1)[0].toInt() and 255; val kind=tag ushr 4; var n=tag and 15
            if((kind==4||kind==5||kind==6||kind==10||kind==13)&&n==15) {
                val t=take(1)[0].toInt() and 255; require(t ushr 4==1 && (t and 15)<=3)
                val width=1 shl (t and 15); n=uint(p,width).also {require(it in 0..4*1024*1024)}.toInt(); take(width)
            }
            fun ref():Int { val r=uint(p,rs); require(r in 0 until count); take(rs); return r.toInt() }
            val v:Any?=when(kind) {
                0 -> when(tag) { 0->null; 8->false; 9->true; else->error("Unsupported plist simple") }
                1 -> { require(n<=3); val width=1 shl n; uint(p,width).also {take(width)} }
                2 -> { require(n==2||n==3); val d=take(1 shl n); if(n==2)ByteBuffer.wrap(d).float.toDouble() else ByteBuffer.wrap(d).double }
                4 -> take(n)
                5 -> take(n).toString(Charsets.US_ASCII)
                6 -> { require(n<=2*1024*1024); take(n*2).toString(Charsets.UTF_16BE) }
                10 -> { require(n<=65535); val refs=List(n){ref()}; refs.map {read(it,depth+1)} }
                13 -> {
                    require(n<=65535); val keys=List(n){ref()}; val vals=List(n){ref()}
                    linkedMapOf<String,Any?>().apply { keys.forEachIndexed {i,k -> val s=read(k,depth+1); require(s is String && !containsKey(s)); put(s,read(vals[i],depth+1)) } }
                }
                else -> error("${"Unsupported plist marker "}${tag}")
            }
            visiting.remove(idx); cache[idx]=v; return v
        }
        return read(root.toInt(),0)
    }
    @Suppress("UNCHECKED_CAST")
    fun dictionary(b:ByteArray)=decode(b) as? Map<String,Any?> ?: error("Expected plist dictionary")
}
