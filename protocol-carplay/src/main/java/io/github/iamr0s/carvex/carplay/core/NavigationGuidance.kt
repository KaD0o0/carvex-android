package io.github.iamr0s.carvex.carplay.core

/**
 * 导航路线引导的**订阅状态**（第 16 章 §2.1–§2.4）。
 *
 * 车机用 `5200` 开始路线引导（参数 0 是可重复的 u16 显示组件 id，参数 1..5 必须空载荷且各至多一次，
 * `≥6` 忽略），`5203` 停止。订阅生效后手机才应发 `5201`/`5202`/`5204`；运行期只把已确认的
 * CarWith HUD 字段接入 `5201`，未确认的机动/车道枚举仍不主动伪造。
 */
object NavigationGuidance {
    /** `5200` 解析结果：组件表与 mask 位。 */
    data class Start(val components: List<Int>, val mask: Int)
    data class RouteUpdate(val components:List<Int>,val state:Int?=null,val maneuverState:Long?=null,
        val currentRoad:String?=null,val destination:String?=null,val eta:Long?=null,val timeRemaining:Long?=null,
        val distanceRemaining:Long?=null,val distanceDisplay:String?=null,val distanceUnits:Int?=null,
        val distanceToManeuver:Long?=null,val distanceToManeuverDisplay:String?=null,val distanceToManeuverUnits:Int?=null,
        val maneuverList:List<Int> = emptyList(),val maneuverCount:Int?=null,val beingShown:Boolean?=null,
        val laneIndex:Int?=null,val laneTotal:Int?=null,val laneShowing:Boolean?=null,val sourceName:String?=null,
        val sourceSupports:Boolean?=null,val timezoneOffset:Int?=null,val stopType:Int?=null,
        val arrivalBattery:Long?=null,val departureBattery:Long?=null,val finalWaypointBattery:Long?=null)
    data class ManeuverUpdate(val components:List<Int>,val index:Int?=null,val description:String?=null,
        val type:Int?=null,val afterRoad:String?=null,val distance:Long?=null,val distanceDisplay:String?=null,
        val distanceUnits:Int?=null,val drivingSide:Int?=null,val junctionType:Int?=null,
        val angles:List<Int> = emptyList(),val exitAngle:Int?=null,val linkedLaneIndex:Int?=null,val exitInfo:String?=null)
    data class Lane(val index:Int?=null,val status:Int?=null,val angles:List<Int> = emptyList(),val highlight:Int?=null)
    data class LaneUpdate(val components:List<Int>,val index:Int?=null,val lanes:List<Lane> = emptyList(),val instruction:String?=null)

    @Volatile var components: List<Int> = emptyList(); private set
    @Volatile var active: Boolean = false; private set
    /** 位 0=p1、1=p2、2=p3、4=p4、5=p5（位 3 由固件另按 `5204` 是否可发出决定，不是参数）。 */
    @Volatile var mask: Int = 0; private set

    /** Firmware feature-17 gate: 5200/5203 are accessory→phone and 5201/5202 are phone→accessory.
     * 5204 is optional and therefore does not participate in the required-group check. */
    fun featureReady(accessorySends: Set<Int>, accessoryReceives: Set<Int>): Boolean =
        setOf(0x5200, 0x5203).all { it in accessorySends } &&
            setOf(0x5201, 0x5202).all { it in accessoryReceives }

    /**
     * 解析并生效一次 `5200`。返回 null 表示**按固件应当整条拒绝**：
     * 参数 1..5 非空载荷或重复、参数 0 长度不是 2。
     */
    fun start(fields: IapFields): Start? {
        val ids = mutableListOf<Int>()
        var localMask = 0
        for ((id, payload) in fields.ordered) {
            when {
                id == 0 -> {
                    if (payload.size != 2) return null
                    ids += ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                }
                id in 1..5 -> {
                    if (payload.isNotEmpty()) return null
                    val bit = 1 shl (if (id <= 3) id - 1 else id - 0) // p1..p3 → 0..2, p4/p5 → 4/5
                    if (localMask and bit != 0) return null
                    localMask = localMask or bit
                }
                else -> Unit // ≥6：固件只记日志后忽略
            }
        }
        val result = Start(ids, localMask)
        components = ids
        mask = localMask
        active = true
        return result
    }

    /** `5203`：调用方先通过 feature17/navMainList 闸门；这里只接受参数 0（u16 组件 id，可重复），
     * 其它 id 忽略。空入参按固件传递空组件表并停止；参数 0 的坏长度按固件返回 0，并且不清除已有会话。
     * 其它参数只记录后忽略。 */
    fun stop(fields: IapFields): Boolean {
        val ids = mutableListOf<Int>()
        for ((id, payload) in fields.ordered) {
            if (id != 0) continue
            if (payload.size != 2) return false
            ids += ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        }
        active = false
        components = ids
        mask = 0
        return true
    }

    /** 上游消息编码器；没有导航数据源时不会被调用，但字段宽度/类型按 ch16 §2.2–§2.5 校验。 */
    fun routeGuidanceUpdate(values: List<Pair<Int, ByteArray>>): List<Pair<Int, ByteArray>> =
        validate(RG_TYPES, values)

    fun maneuverUpdate(values: List<Pair<Int, ByteArray>>): List<Pair<Int, ByteArray>> =
        validate(MANEUVER_TYPES, values)

    fun laneGuidance(values: List<Pair<Int, ByteArray>>): List<Pair<Int, ByteArray>> =
        validate(LANE_TYPES, values)

    /** Build the exact repeated component and nested-group TLVs used by the firmware senders. */
    fun route(update:RouteUpdate):List<Pair<Int,ByteArray>> = buildList {
        update.components.forEach{add(0 to IapWire.be16(it))}
        fun u8(id:Int,v:Int?){v?.let{require(it in 0..255);add(id to byteArrayOf(it.toByte()))}}
        fun u16(id:Int,v:Int?){v?.let{add(id to IapWire.be16(it))}}
        fun u32(id:Int,v:Long?){v?.let{add(id to IapWire.be32(it))}}
        fun u64(id:Int,v:Long?){v?.let{add(id to IapWire.be64(it))}}
        fun text(id:Int,v:String?){v?.let{require(NowPlayingUpdate.textOrNull(it)!=null);add(id to IapWire.cstring(it))}}
        u8(1,update.state);u64(2,update.maneuverState);text(3,update.currentRoad);text(4,update.destination)
        u64(5,update.eta);u64(6,update.timeRemaining);u32(7,update.distanceRemaining);text(8,update.distanceDisplay)
        u8(9,update.distanceUnits);u32(10,update.distanceToManeuver);text(11,update.distanceToManeuverDisplay)
        u8(12,update.distanceToManeuverUnits);update.maneuverList.forEach{add(13 to IapWire.be16(it))};u16(14,update.maneuverCount)
        update.beingShown?.let{add(15 to byteArrayOf(if(it)1 else 0))};u16(16,update.laneIndex);u16(17,update.laneTotal)
        update.laneShowing?.let{add(18 to byteArrayOf(if(it)1 else 0))}
        if(mask and (1 shl 0) != 0){text(19,update.sourceName);update.sourceSupports?.let{add(20 to byteArrayOf(if(it)1 else 0))}}
        // Firmware stores p4 at bit 4. Bit 3 is the derived 5204 outgoing-capability flag.
        if(mask and (1 shl 4) != 0){update.timezoneOffset?.let{require(it in 0..65535);add(21 to IapWire.be16(it))}}
        if(mask and (1 shl 5) != 0)u8(22,update.stopType)
        u32(24,update.arrivalBattery);u32(25,update.departureBattery);u32(26,update.finalWaypointBattery)
    }.let(::routeGuidanceUpdate)

    fun maneuver(update:ManeuverUpdate):List<Pair<Int,ByteArray>> = buildList {
        update.components.forEach{add(0 to IapWire.be16(it))}
        fun u8(id:Int,v:Int?){v?.let{require(it in 0..255);add(id to byteArrayOf(it.toByte()))}}
        fun u16(id:Int,v:Int?){v?.let{add(id to IapWire.be16(it))}}
        fun u32(id:Int,v:Long?){v?.let{add(id to IapWire.be32(it))}}
        fun text(id:Int,v:String?){v?.let{require(NowPlayingUpdate.textOrNull(it)!=null);add(id to IapWire.cstring(it))}}
        u16(1,update.index);text(2,update.description);u8(3,update.type);text(4,update.afterRoad);u32(5,update.distance)
        text(6,update.distanceDisplay);u8(7,update.distanceUnits);u8(8,update.drivingSide);u8(9,update.junctionType)
        update.angles.forEach{u16(10,it)};u16(11,update.exitAngle);u16(12,update.linkedLaneIndex);text(13,update.exitInfo)
    }.let(::maneuverUpdate)

    fun lane(update:LaneUpdate):List<Pair<Int,ByteArray>> = buildList {
        update.components.forEach{add(0 to IapWire.be16(it))};update.index?.let{add(1 to IapWire.be16(it))}
        update.instruction?.let{require(NowPlayingUpdate.textOrNull(it)!=null);add(3 to IapWire.cstring(it))}
        update.lanes.forEach{lane->
            val children=buildList<Pair<Int,ByteArray>>{
                lane.index?.let{add(0 to IapWire.be16(it))};lane.status?.let{require(it in 0..255);add(1 to byteArrayOf(it.toByte()))}
                lane.angles.forEach{add(2 to IapWire.be16(it))};lane.highlight?.let{add(3 to IapWire.be16(it))}
            }
            if(children.isNotEmpty())add(2 to IapWire.fields(children))
        }
    }.let(::laneGuidance)

    private fun validate(types: Map<Int, String>, values: List<Pair<Int, ByteArray>>): List<Pair<Int, ByteArray>> {
        for ((id, payload) in values) {
            val type = requireNotNull(types[id]) { "Unsupported navigation parameter "+id }
            when (type) {
                "u8" -> require(payload.size == 1)
                "u16" -> require(payload.size == 2)
                "u32" -> require(payload.size == 4)
                "u64" -> require(payload.size == 8)
                "u16*" -> require(payload.isNotEmpty() && payload.size % 2 == 0)
                "str" -> require(payload.isNotEmpty() && payload.last() == 0.toByte())
                "group" -> require(payload.isNotEmpty())
                else -> error("Unknown type "+type)
            }
        }
        return values
    }

    /** 5201 参数 id → 编码类型（ch16 §2.2）。 */
    private val RG_TYPES = mapOf(
        0 to "u16*", 1 to "u8", 2 to "u64", 3 to "str", 4 to "str", 5 to "u64", 6 to "u64",
        7 to "u32", 8 to "str", 9 to "u8", 10 to "u32", 11 to "str", 12 to "u8", 13 to "u16*",
        14 to "u16", 15 to "u8", 16 to "u16", 17 to "u16", 18 to "u8", 19 to "str", 20 to "u8",
        21 to "u16", 22 to "u8", 23 to "group", 24 to "u32", 25 to "u32", 26 to "u32")

    /** 5202 参数 id → 编码类型（ch16 §2.3）。 */
    private val MANEUVER_TYPES = mapOf(
        0 to "u16*", 1 to "u16", 2 to "str", 3 to "u8", 4 to "str", 5 to "u32", 6 to "str",
        7 to "u8", 8 to "u8", 9 to "u8", 10 to "u16*", 11 to "u16", 12 to "u16", 13 to "str")

    /** 5204 参数 id → 编码类型（ch16 §2.5）；参数 2 是车道组，可重复。 */
    private val LANE_TYPES = mapOf(0 to "u16*", 1 to "u16", 2 to "group", 3 to "str")
}
