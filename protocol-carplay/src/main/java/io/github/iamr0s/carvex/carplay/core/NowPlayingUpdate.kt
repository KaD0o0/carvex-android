package io.github.iamr0s.carvex.carplay.core

/** NOW_PLAYING_CSM_V1/revision1 (docs/09). Text is UTF-8 with one terminating NUL. */
object NowPlayingUpdate {
    const val START=0x5000
    const val ID=0x5001
    const val STOP=0x5002
    val ids=setOf(START,ID,STOP)
    /** 订阅命令可能在 AirPlay 会话建立之前就到达（识别阶段），此时发布器还不存在。
     *  这里暂存最近一条 5000 的字段，AirSession 建好发布器后取走并立即回一次快照。 */
    @Volatile var pendingSubscription:IapFields?=null
    data class Attribute(val group:Int,val id:Int)
    /** Membership remains exposed for existing callers; ordered entries preserve
     * firmware's repeated subscription parameters for outgoing encoding. */
    data class Selection(val attributes:Set<Attribute>,val startIndexOverride:Long?=null,val infoMask:Int=0,
                         val orderedAttributes:List<Attribute> = attributes.toList())
    enum class PlaybackStatus(val wire:Int){ STOPPED(0),PLAYING(1),PAUSED(2),SEEK_FORWARD(3),SEEK_BACKWARD(4) }
    /** Null means unknown, never stopped/zero/playing. Units here are explicitly milliseconds.
     * queueIndex must already use the selected peer's index convention; it is not inferred. */
    /** Fields mirror the firmware's two 5001 encoders. Nullable values are omitted, as on iOS. */
    data class Track(val title:String?=null,val artist:String?=null,val album:String?=null,
        val durationMs:Long?=null,val elapsedMs:Long?=null,val queueIndex:Long?=null,val queueCount:Long?=null,
        val status:PlaybackStatus?=null,val persistentId:Long?=null,val albumTrackNum:Int?=null,
        val albumTrackCount:Int?=null,val albumDiscNum:Int?=null,val albumDiscCount:Int?=null,
        val genre:String?=null,val composer:String?=null,val isLikeSupported:Boolean?=null,
        val isBanSupported:Boolean?=null,val isLiked:Boolean?=null,val isBanned:Boolean?=null,
        val chapterCount:Int?=null,val queueChapterIndex:Long?=null,val shuffleMode:Int?=null,
        val repeatMode:Int?=null,val appName:String?=null,val mediaLibraryUid:String?=null,
        val itunesRadioAd:Boolean?=null,val itunesRadioStation:String?=null,
        val itunesRadioStationMediaPlaylistPersistentId:Long?=null,val playbackSpeed:Int?=null,
        val setTimeElapsedAvailable:Boolean?=null,val appBundleId:String?=null)
    val implemented=Selection((listOf(0,1,4,6,7,8,9,10,12,0x10,0x12,0x15,0x16,0x17,0x18,0x1b)
        .map{Attribute(0,it)}+listOf(0,1,2,3,4,5,6,7,8,9,10,0xb,0xc,0xd,0x10)
        .map{Attribute(1,it)}).toSet())

    /** 24A446 accessoryd: 0x10008f588 / 0x10008f978, called by START.
     * Protocol initialization, not a claim about the actual player's state.
     * Keep separate from the nullable live snapshot, including on re-subscribe. */
    internal val initialDefaults=Track(
        title="",artist="",album="",durationMs=0,elapsedMs=0,queueIndex=0,queueCount=0,
        status=PlaybackStatus.STOPPED,persistentId=0,albumTrackNum=0,albumTrackCount=0,
        albumDiscNum=0,albumDiscCount=0,genre="",composer="",isLikeSupported=false,
        isBanSupported=false,isLiked=false,isBanned=false,chapterCount=0,
        queueChapterIndex=0,shuffleMode=0,repeatMode=0,appName="",mediaLibraryUid="",
        itunesRadioAd=false,itunesRadioStation="",itunesRadioStationMediaPlaylistPersistentId=0,
        playbackSpeed=0,setTimeElapsedAvailable=false,appBundleId="")

    /** 固件语义（第 17 章 §2.1）：未知顶层参数忽略；重复组继续追加其订阅项；
     *  组 0/2 的子参数必须零长度，组 1 的 `0x11` 例外（U32 startIndexOverride）。
     *  任一子参数带非法值时**整条丢弃且不改状态**——返回 null，由调用方忽略，
     *  不能抛异常把运行期 iAP 链路带下去。 */
    fun selection(fields:IapFields):Selection? {
        if(fields.ordered.size>64)return null
        val selected=linkedSetOf<Attribute>()
        val ordered=mutableListOf<Attribute>()
        var override:Long?=null
        var mediaCount=0
        var playbackCount=0
        for((group,bytes) in fields.ordered){
            if(group !in 0..2)continue
            val children=runCatching{IapWire.parseFieldList(bytes)}.getOrNull()?:return null
            if(children.ordered.size>256)return null
            if(group==2){
                for((_,value) in children.ordered)if(value.isNotEmpty())return null
                continue
            }
            if(group==0){mediaCount+=children.ordered.size;if(mediaCount>28)return null}
            if(group==1){playbackCount+=children.ordered.size;if(playbackCount>18)return null}
            for((id,value) in children.ordered){
                if(group==1&&id==0x11){
                    if(value.size!=4)return null
                    override=((value[0].toLong() and 255) shl 24) or ((value[1].toLong() and 255) shl 16) or
                        ((value[2].toLong() and 255) shl 8) or (value[3].toLong() and 255)
                    val attribute=Attribute(group,id)
                    selected.add(attribute)
                    ordered.add(attribute)
                }else{
                    if(value.isNotEmpty())return null
                    val attribute=Attribute(group,id)
                    selected.add(attribute)
                    ordered.add(attribute)
                }
            }
        }
        var infoMask=0
        fields.ordered.filter{it.first==2}.forEach{groupBytes->
            val children=runCatching{IapWire.parseFieldList(groupBytes.second)}.getOrNull()?:return null
            for((id,value) in children.ordered){if(value.isNotEmpty()||id !in 0..31)return null;infoMask=infoMask or (1 shl id)}
        }
        return Selection(selected.toSet(),override,infoMask,ordered.toList())
    }
    /** Null is unknown; an explicit empty string remains an update containing one NUL byte.
     * Reject unpaired UTF-16 surrogates before JVM encoding can silently replace them. */
    fun textOrNull(value:String?):String? {
        if(value==null)return null
        var index=0
        while(index<value.length){
            val c=value[index]
            if(c=='\u0000'||c.isLowSurrogate())return null
            if(c.isHighSurrogate()){
                if(index+1>=value.length||!value[index+1].isLowSurrogate())return null
                index+=2
            }else index++
        }
        return value.takeIf{it.toByteArray(Charsets.UTF_8).size<=4096}
    }
    fun fields(track:Track,selection:Selection=implemented):List<Pair<Int,ByteArray>> {
        val groups=linkedMapOf<Int,MutableList<Pair<Int,ByteArray>>>()
        fun add(group:Int,id:Int,bytes:ByteArray){
            val attribute=Attribute(group,id)
            val repetitions=selection.orderedAttributes.count{it==attribute}
            repeat(repetitions){groups.getOrPut(group){mutableListOf()}.add(id to bytes.copyOf())}
        }
        fun text(group:Int,id:Int,value:String?){value?.let{
            require(textOrNull(it)!=null)
            add(group,id,IapWire.cstring(it))
        }}
        track.persistentId?.let{add(0,0,IapWire.be64(it))}
        text(0,1,track.title)
        track.durationMs?.let{add(0,4,IapWire.be32(it))}
        text(0,6,track.album)
        fun u16(group:Int,id:Int,value:Int?){value?.let{require(it in 0..65535);add(group,id,IapWire.be16(it))}}
        u16(0,7,track.albumTrackNum);u16(0,8,track.albumTrackCount);u16(0,9,track.albumDiscNum);u16(0,10,track.albumDiscCount)
        text(0,12,track.artist);text(0,0x10,track.genre);text(0,0x12,track.composer)
        fun bool(group:Int,id:Int,value:Boolean?){value?.let{add(group,id,byteArrayOf(if(it)1 else 0))}}
        bool(0,0x15,track.isLikeSupported);bool(0,0x16,track.isBanSupported);bool(0,0x17,track.isLiked);bool(0,0x18,track.isBanned)
        u16(0,0x1b,track.chapterCount)
        track.status?.let{add(1,0,byteArrayOf(it.wire.toByte()))}
        track.elapsedMs?.let{add(1,1,IapWire.be32(it))}
        track.queueIndex?.let{add(1,2,IapWire.be32(it))}
        track.queueCount?.let{add(1,3,IapWire.be32(it))}
        track.queueChapterIndex?.let{add(1,4,IapWire.be32(it))}
        track.shuffleMode?.let{require(it in 0..255);add(1,5,byteArrayOf(it.toByte()))}
        track.repeatMode?.let{require(it in 0..255);add(1,6,byteArrayOf(it.toByte()))}
        text(1,7,track.appName);text(1,8,track.mediaLibraryUid);bool(1,9,track.itunesRadioAd);text(1,10,track.itunesRadioStation)
        track.itunesRadioStationMediaPlaylistPersistentId?.let{add(1,0xb,IapWire.be64(it))}
        track.playbackSpeed?.let{require(it in 0..65535);add(1,0xc,IapWire.be16(it))}
        bool(1,0xd,track.setTimeElapsedAvailable);text(1,0x10,track.appBundleId)
        // Firmware walks each subscription array, including repeats, in order.
        return groups.map{(id,values)->
            val byId=values.associate{it.first to it.second}
            id to IapWire.fields(selection.orderedAttributes.filter{it.group==id}
                .mapNotNull{a->byId[a.id]?.let{a.id to it}})
        }
    }
    fun encode(track:Track):ByteArray {
        val fields=fields(track);require(fields.isNotEmpty())
        return IapWire.csm(ID,fields)
    }
}

/** One authenticated Link owns the subscription. Backend snapshots may precede Link readiness.
 * All mutations and sends serialize: STOP cancels uncommitted updates, not bytes already sent. */
class NowPlayingPublisher(private val emit:(List<Pair<Int,ByteArray>>)->Boolean) {
    private var ready=false
    private var selection:NowPlayingUpdate.Selection?=null
    private var snapshot=NowPlayingUpdate.Track()
    @Synchronized fun linkReady(ready:Boolean){this.ready=ready;selection=null}
    @Synchronized fun command(id:Int,fields:IapFields):Boolean {
        if(!ready)return false
        when(id){
            // 非法订阅整条丢弃，不改已有 selection（固件返回 0，不中止会话）。
            NowPlayingUpdate.START->{
                val selected=NowPlayingUpdate.selection(fields)?:return false
                selection=null
                val initial=NowPlayingUpdate.fields(NowPlayingUpdate.initialDefaults,selected)
                // A failed enqueue must not let a real update overtake initialization.
                if(initial.isNotEmpty()&&!emit(initial))return false
                selection=selected
                // Our backend may already have delivered a snapshot before START.
                // Publish it after the independent default message, never instead.
                val current=NowPlayingUpdate.fields(snapshot,selected)
                return current.isEmpty() || emit(current)
            }
            // 5002 没有参数，handler 不读 param；多余参数按固件忽略。
            NowPlayingUpdate.STOP->selection=null
            else->return false
        }
        return true
    }
    /** `5003`：参数 0/1 是 U32（elapsed/queueIndex），参数 2 是 S32 startIndex（-1=自动）。
     *  三个都可缺；只有参数 2 且 5000 未声明 +0x51(startIndexOverride) 时返回 false。 */
    @Synchronized fun setInfo(fields:IapFields):Boolean {
        var elapsed:Long?=null;var index:Long?=null;var start:Int?=null
        for((id,bytes) in fields.ordered){
            when(id){
                0->{if(bytes.size!=4)return false;elapsed=be32(bytes)}
                1->{if(bytes.size!=4)return false;index=be32(bytes)}
                2->{if(bytes.size!=4)return false;start=be32(bytes).toInt()}
                else->Unit
            }
        }
        if(elapsed==null&&index==null&&start==null)return false
        if(elapsed==null&&index==null&&start!=null&&selection?.startIndexOverride==null)return false
        if(elapsed!=null||index!=null){
            snapshot=snapshot.copy(elapsedMs=elapsed?:snapshot.elapsedMs,queueIndex=index?:snapshot.queueIndex)
            return send(snapshot)
        }
        return true
    }
    private fun be32(b:ByteArray):Long=((b[0].toLong() and 255) shl 24) or ((b[1].toLong() and 255) shl 16) or
        ((b[2].toLong() and 255) shl 8) or (b[3].toLong() and 255)
    @Synchronized fun update(delta:NowPlayingUpdate.Track,replaceMedia:Boolean=false):Boolean {
        // Validate even before a subscriber exists, so bad ranges are never cached.
        NowPlayingUpdate.fields(delta)
        val old=if(replaceMedia)NowPlayingUpdate.Track() else snapshot
        snapshot=NowPlayingUpdate.Track(delta.title?:old.title,delta.artist?:old.artist,delta.album?:old.album,
            delta.durationMs?:old.durationMs,delta.elapsedMs?:old.elapsedMs,delta.queueIndex?:old.queueIndex,
            delta.queueCount?:old.queueCount,delta.status?:old.status,delta.persistentId?:old.persistentId,
            delta.albumTrackNum?:old.albumTrackNum,delta.albumTrackCount?:old.albumTrackCount,
            delta.albumDiscNum?:old.albumDiscNum,delta.albumDiscCount?:old.albumDiscCount,delta.genre?:old.genre,
            delta.composer?:old.composer,delta.isLikeSupported?:old.isLikeSupported,delta.isBanSupported?:old.isBanSupported,
            delta.isLiked?:old.isLiked,delta.isBanned?:old.isBanned,delta.chapterCount?:old.chapterCount,
            delta.queueChapterIndex?:old.queueChapterIndex,delta.shuffleMode?:old.shuffleMode,delta.repeatMode?:old.repeatMode,
            delta.appName?:old.appName,delta.mediaLibraryUid?:old.mediaLibraryUid,delta.itunesRadioAd?:old.itunesRadioAd,
            delta.itunesRadioStation?:old.itunesRadioStation,
            delta.itunesRadioStationMediaPlaylistPersistentId?:old.itunesRadioStationMediaPlaylistPersistentId,
            delta.playbackSpeed?:old.playbackSpeed,delta.setTimeElapsedAvailable?:old.setTimeElapsedAvailable,
            delta.appBundleId?:old.appBundleId)
        return send(delta)
    }
    private fun send(track:NowPlayingUpdate.Track):Boolean {
        val selected=selection?:return false
        if(!ready)return false
        val fields=NowPlayingUpdate.fields(track,selected)
        return fields.isNotEmpty()&&emit(fields)
    }
}
