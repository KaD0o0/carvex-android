package io.github.iamr0s.carvex.carplay.core

/** 对端自报的版本标识。三项都可能缺失：不同车机、不同阶段能拿到的东西不一样。 */
data class PeerVersion(
    /** Bonjour 的 `protovers`，或 `/info` 的 `protocolVersion`。 */
    val airplayProtocol:String?=null,
    /** Bonjour 的 `srcvers`，或 `/info` 的 `sourceVersion`。 */
    val airplaySource:String?=null,
    /** 经链路协商的控制会话版本，不能用 linkVersion 或本地配置值代替。 */
    val controlSession:Int?=null
)

/** 选择 profile 的依据：版本标识 + 已经观察到的能力证据。
 *
 * 为什么版本标识不足以单独做判据：文档明确 `210.81` 只是**本车所带 SDK 的版本**，无线与有线车机
 * 都可能报同一个值，"按值分支"必须有值可辨才行。真正区分行为的是对端**实际发了什么**，
 * 所以能力证据是主判据，版本标识用于日志、记录以及将来出现可靠映射时的细分。
 * 接口本身对二者都开放（[IapPeerProfile.accepts] 拿得到整个证据）。 */
data class PeerEvidence(
    val version:PeerVersion=PeerVersion(),
    val observedMessageIds:Set<Int> = emptySet(),
    val sawLingoPayload:Boolean=false
) {
    /** 累积一条观测：记下消息 id，并判断它的载荷是不是 iAP1 风格的 lingo 包。 */
    fun observing(id:Int,payload:ByteArray)=observing(id,IapMessageRouter.lingoMessage(payload)!=null)

    /** 调用方已经分过类时的入口（`true` 表示载荷是 lingo 包），省去重复解析。 */
    fun observing(id:Int,lingoPayload:Boolean)=copy(
        observedMessageIds=observedMessageIds+id,
        sawLingoPayload=sawLingoPayload||lingoPayload)
}

/** 一个对端版本/能力组合对应的客户端行为集。
 *
 * 选择在握手早期做，之后各环节都走选中的那个实例 —— 这就是"按版本进入不同实现"的落点，
 * 而不是在每个分支里回头再判一次版本。接口的默认实现是**什么都不预设**的保守行为，
 * 具体的 profile 只覆盖它真正知道的那部分。 */
interface IapPeerProfile {
    val name:String

    fun accepts(evidence:PeerEvidence):Boolean

    /** 这条消息按**本实现**的理解属于哪个族。默认：不认识。
     *  注意它表达的是"归不归我管"，不是客观分类：无线实现不会把 lingo 载荷认成自己的。 */
    fun classify(command:IapCommand):IapMessageRouter.Route=IapMessageRouter.Route.Unknown(command.id,0)

    /** 文档风格的 lingo 认证命令（lingo 0 的 `0x15`/`0x18`/`0x1a`）。默认：不认识，记录。 */
    fun lingoAuthenticationAction(lingo:Int,command:Int):IapAuthentication.Action=IapAuthentication.Action.RECORD

    /** 无线族的 iAP2 认证消息（`0xAA0x`）。默认：不认识，记录。
     *  与 [lingoAuthenticationAction] 分开，是因为两族的认证寻址空间本来就不同 —— 一个按 lingo 命令，
     *  一个按 message id；共用一个入口会让其中一族按另一族的口径回答。 */
    fun messageAuthenticationAction(id:Int):IapAuthentication.Action=IapAuthentication.Action.RECORD

    /** lingo 查询的预期。默认：未确认。 */
    fun lingoExpectation(lingo:Int,command:Int):IapLingoCatalog.Expectation=IapLingoCatalog.Expectation.UNCONFIRMED

    /** 对端请求的 EA 协议（名字或 ID 文本）。默认：接受任意名字，不因为不认识而丢弃。 */
    fun eaSession(protocol:String?):EaSessionPolicy.Decision=EaSessionPolicy.decide(protocol)
}

/** 没有任何可用证据时的兜底：什么都不预设，只记录。 */
class UnknownPeerProfile:IapPeerProfile {
    override val name="unknown"
    override fun accepts(evidence:PeerEvidence)=true
}

/** 固件文档所描述的那一版（样本：Nissan NTDA3 / P350 有线车机）：业务走 iAP1 风格的 lingo 消息。 */
class DocumentedWiredProfile:IapPeerProfile {
    override val name="documented-wired"
    override fun accepts(evidence:PeerEvidence)=evidence.sawLingoPayload
    /** 只认 lingo 包；无线族的 message id 不归它管。 */
    override fun classify(command:IapCommand)=when(command){
        is IapCommand.Lingo->IapMessageRouter.Route.Lingo(command.message)
        else->IapMessageRouter.Route.Unknown(command.id,0)
    }
    /** 这一族的认证是 lingo 0 的 `0x1a`（对端索取客户端认证信息）→ 要 MFi 芯片签名。 */
    override fun lingoAuthenticationAction(lingo:Int,command:Int)=IapAuthentication.action(lingo,command)
    override fun lingoExpectation(lingo:Int,command:Int)=IapLingoCatalog.expectation(lingo,command)
}

/** 本项目此前实现的无线 CarPlay 一族（BT SPP 引导 + Wi-Fi 承载）。 */
class WirelessCarplayProfile:IapPeerProfile {
    override val name="wireless-carplay"
    override fun accepts(evidence:PeerEvidence)=evidence.observedMessageIds.any{it in IapMessageRouter.wirelessIds}
    /** 只认自己那一族的 message id；lingo 载荷不归它管。 */
    override fun classify(command:IapCommand)=if(command.id in IapMessageRouter.wirelessIds)
        IapMessageRouter.Route.Wireless(command.id) else IapMessageRouter.Route.Unknown(command.id,0)
    /** 这一族的认证走 `0xAA0x`：车机出示证书与签名，客户端验证、不签名。
     * AA/06 是认证代际线索，归此协议族处理，但只能记录，不能当成验签成功。 */
    override fun messageAuthenticationAction(id:Int)=when(id){
        0xaa01,0xaa03,0xaa04,0xaa05->IapAuthentication.Action.ACCEPT
        else->IapAuthentication.Action.RECORD
    }
}

object IapPeerProfiles {
    val documentedWired=DocumentedWiredProfile()
    val wireless=WirelessCarplayProfile()
    val unknown=UnknownPeerProfile()

    /** 顺序即优先级：证据越具体的越靠前；[unknown] 只作兜底，不参与匹配。 */
    private val ordered=listOf(documentedWired,wireless)

    fun select(evidence:PeerEvidence):IapPeerProfile=ordered.firstOrNull{it.accepts(evidence)}?:unknown
}
