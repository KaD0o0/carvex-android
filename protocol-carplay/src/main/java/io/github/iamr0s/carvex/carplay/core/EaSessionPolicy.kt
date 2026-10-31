package io.github.iamr0s.carvex.carplay.core
/** EA 会话的接受策略 —— carplay-client-spec §3.8 / §6.1。
 *
 * 文档只记录了**这一台车机**的协议名（`com.hsae.datatransfer`，来自 `carplay_config.xml`），
 * 但客户端面对的是各种固件版本。按名字做白名单会把别的车机直接挡在门外，与"兼容所有"相反；
 * 因此这里的策略是：**接受对端请求的任意协议名并记录**，只保留最小的合法性检查，
 * 让上层拿到真实名字后再决定怎么用它。
 */
object EaSessionPolicy {
    private const val MAX_NAME=128

    sealed interface Decision {
        /** 接受会话，[protocolName] 是对端实际请求的名字（不要用文档里的样本值替代它）。 */
        data class Accept(val protocolName:String):Decision
        /** 连名字都不合法（缺失或过长），无法记录，拒绝。 */
        data class Reject(val protocolName:String,val reason:String):Decision
    }

    fun decide(protocolName:String?):Decision {
        val name=protocolName?.trim().orEmpty()
        if(name.isEmpty())return Decision.Reject("","${"peer gave no EA protocol name"}")
        if(name.length>MAX_NAME)return Decision.Reject(name.take(MAX_NAME),"${"EA protocol name exceeds "}${MAX_NAME}${" characters"}")
        return Decision.Accept(name)
    }
}
