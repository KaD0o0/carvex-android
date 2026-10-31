package io.github.iamr0s.carvex.sessioncore

/** CarPlay 侧发来的按键事件标识。
 *
 *  一个物理按键的"按下"和"抬起"在 CarPlay 侧是**两个事件**：可能是同一个 usage 的 down/up，
 *  也可能是两个不同的 usage。所以标识里带上拍（`:down`/`:up`）。运行时按协议事件直接映射；
 *  这些标识仍供监听模式和诊断界面显示。
 */
object CarPlayEvents {
    /** 界面焦点请求：deviceOfferFocus（一次性）。 */
    const val FOCUS = "focus"

    /** 任意页面（prefix）+ usage + 拍：media（0x0C）/ keyboard（0x07）共用这一套 id 格式。 */
    fun key(prefix:String,usage:Int,down:Boolean) = prefix + ":" + usage.toString(16) + phase(down)

    /** Consumer Control（0x0C）里的媒体键 usage 的按下/抬起。 */
    fun media(usage:Int,down:Boolean) = key("media",usage,down)

    /** Keyboard（0x07）里的 usage 的按下/抬起：车机的方向/旋钮键通常在这里。 */
    fun keyboard(usage:Int,down:Boolean) = key("keyboard",usage,down)

    /** 界面请求 URL，归一到 scheme（`maps:` / `nowplaying:` / `mobilephone:` …），一次性。 */
    fun ui(url:String):String {
        val trimmed = url.trim()
        val colon = trimmed.indexOf(':')
        val scheme = if (colon > 0) trimmed.substring(0, colon).trim().lowercase() else ""
        return if (scheme.isNotEmpty()) "ui:$scheme:" else "ui:" + trimmed.lowercase()
    }

    /** 语音请求的 action 原文（文档没给取值集合，按原样保留），一次性。 */
    fun voice(action:String) = "voice:" + action.trim().lowercase()

    /** 未识别的 HID 设备（既不是消费页 0x0C 也不是键盘页 0x07）：只在学习模式里列出，供诊断。 */
    fun unknownHid(uuid:String?) = "hid:" + (uuid ?: "?")

    private fun phase(down:Boolean) = if (down) ":down" else ":up"

    /** 上下文后缀：绑定键 = CarWith 键码 + 上下文后缀。空串表示"任意状态"，作为兜底。 */
    const val CONTEXT_ANY = ""
    const val CONTEXT_IDLE = "@idle"
    const val CONTEXT_VOICE = "@voice"

    /** 绑定键里的 CarWith 键码（键码可能带上下文后缀）。解析不出就是旧格式，调用方应忽略。 */
    fun codeOf(bindingKey:String):Int? = bindingKey.substringBefore('@').toIntOrNull()
    /** 绑定键里的上下文后缀（空串 = 任意状态）。 */
    fun contextOf(bindingKey:String):String =
        bindingKey.substringAfter('@', "").let { if (it.isEmpty()) "" else "@$it" }
    fun contextLabel(context:String) = when (context) {
        CONTEXT_IDLE -> "（语音空闲）"; CONTEXT_VOICE -> "（语音进行中）"; else -> ""
    }

    private val mediaNames = mapOf(0xb0 to "播放", 0xb1 to "暂停", 0xb5 to "下一曲", 0xb6 to "上一曲")

    private fun phaseLabel(eventId:String) =
        when {
            eventId.endsWith(":down") -> "（按下）"
            eventId.endsWith(":up") -> "（抬起）"
            else -> ""
        }

    /** 给人看的名字。未知的也照实描述，不隐藏 —— 学习模式列出的就是车机真发来的东西。 */
    fun label(id:String):String = when {
        id == FOCUS -> "界面焦点"
        id.startsWith("media:") -> {
            val raw = id.removePrefix("media:").substringBefore(':')
            val name = raw.toIntOrNull(16)?.let { mediaNames[it] }
            (if (name != null) "媒体键 · $name" else "媒体键 0x$raw") + phaseLabel(id)
        }
        id.startsWith("keyboard:") ->
            "键盘 usage 0x" + id.removePrefix("keyboard:").substringBefore(':') + phaseLabel(id)
        id.startsWith("ui:") -> "界面请求 " + id.removePrefix("ui:")
        id.startsWith("voice:") -> "语音 " + id.removePrefix("voice:")
        id.startsWith("hid:") -> "未识别 HID " + id.removePrefix("hid:")
        id.startsWith("page") -> {
            val page=id.removePrefix("page").substringBefore(':')
            val usage=id.substringAfter(':').substringBefore(':')
            "页面 0x$page usage 0x$usage" + phaseLabel(id)
        }
        else -> id
    }
}
