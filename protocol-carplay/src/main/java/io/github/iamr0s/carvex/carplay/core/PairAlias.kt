package io.github.iamr0s.carvex.carplay.core

/** 车机配对记录的查找键：**receiver 标识 + Bonjour 的 pairing id（`pi`）**。
 *
 * 为什么不能只看 receiver（改造前的做法）：`receiver` 来自 0x4301，是车机自报的标识，**不同车机可能相同**
 * —— 现场日志里两台都由 DiPlay 实现的车机（`DiPlay CS11` / `DiPlay 领克`，`srcvers` 都报 `950.7.1`）
 * 就会共用它。两台车于是命中同一条记录：后配的那台覆盖先配的，之后无论连哪一台都会报
 * "Pinned peer key conflicts with receiver metadata"，而且应用内没有恢复入口。
 *
 * `pi` 是车机在 Bonjour 里通告的身份标识，同一实现的两台车不会相同，因此把它并入键即可把两台车分开。
 * 它缺失或不可用时**退化为只用 receiver** —— 与改造前行为逐字相同，旧记录与不报 `pi` 的实现都不受影响。
 *
 * 公钥（`pk`）**不参与键，只参与比对**。若把它也做进键，等于"车机换了身份就查不到记录、自动重新配对"，
 * 那会把 pin 从"记住并拒绝变化"退化成纯缓存，丢掉它在此拓扑里唯一的作用（TOFU 防护）。
 * 换句话说：键解决"两台车串用"，`pk` 比对继续负责"同一台车换了身份要拒绝"。
 */
object PairAlias {
    /** `pi` 的长度上限。观测到的形态有 UUID 与十六进制串，都很短；上限只是防止把脏值当键。 */
    private const val MAX=64

    /** 规范化 `pi`：去首尾空白、转小写；空、超长或含空白/控制字符的按"没有 pi"处理。
     *
     * 宁可退化也不猜：用脏值造键会得到永远命不中的键，症状是"每次连接都重新配对"（pin 静默失效），
     * 比直接退化成只用 receiver 更难发现。 */
    fun pairingId(raw:String?):String? {
        val text=raw?.trim()?.lowercase()?:return null
        if(text.isEmpty()||text.length>MAX)return null
        if(text.any{it.isWhitespace()||it.isISOControl()})return null
        return text
    }

    /** 精确键：有合法 `pi` 时 `<receiver>.<pi>`，否则就是 `<receiver>`。
     *  `receiver` 由调用方先经 [ReceiverIdentity.canonical] 归一（12 位小写十六进制、不含 `.`），
     *  所以分隔符不会与内容歧义。 */
    fun primary(receiver:String,pairingId:String?):String =
        pairingId(pairingId)?.let{receiver+"."+it}?:receiver

    /** 回退键：只有 receiver。用于读升级前写下的旧记录，以及 `pi` 在两次连接间发生变化时的兜底。 */
    fun fallback(receiver:String):String=receiver
}
