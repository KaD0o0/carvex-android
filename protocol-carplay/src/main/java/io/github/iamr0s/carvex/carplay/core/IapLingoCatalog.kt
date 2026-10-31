package io.github.iamr0s.carvex.carplay.core
/** 文档记录下来的 lingo 命令清单 —— gap-closure §3.1 从配件侧 58 个 `create_iap_packet`
 *  调用点回溯得到的 (lingo, command) 表。
 *
 * 这张表描述的是**对端会发什么**，不是客户端该硬编码的行为。客户端要兼容的不止一个固件版本，
 * 所以它只能作为"识别已知消息"的参照：命中就按 [Expectation] 处理，未命中就当作未知消息
 * 记录后继续（见 [expectation] 对未知返回 `UNCONFIRMED`），绝不能当作"对端一定会按这份
 * 清单、这个顺序出现"的剧本。
 *
 * 表里**不含**应答命令的编号与载荷取值：gap-closure §3.2 明确 option 位「固件内无常量表，
 * 位含义需抓包确认」，§3.1 也只列了调用点自身的 (lingo, command)。这两项在抓到真实报文前
 * 一律不编造，[Expectation.ANSWER] 只表示"这条命需要客户端应答"，不表示已知该怎么答。
 */
object IapLingoCatalog {
    /** 客户端对一条已知命令该做什么。 */
    enum class Expectation {
        /** 对端在索取信息，客户端需要应答（具体应答内容另行确定）。 */
        ANSWER,
        /** 对端在通报信息，客户端接收并据此调整自己的行为。 */
        ACCEPT,
        /** 文档没有写明职责或方向，实现不得据此推断行为。 */
        UNCONFIRMED
    }

    data class Command(val lingo:Int,val command:Int,val name:String,val meaning:String,val expectation:Expectation)

    val commands:List<Command> = listOf(
        // lingo 0：通用（通用控制、认证、UI 模式）
        Command(0,0x0f,"ipod_get_ipod_lingo_protocol_version","${"query a lingo's protocol version"}",Expectation.ANSWER),
        Command(0,0x4b,"ipod_get_ipod_option_for_lingo","${"query a lingo's options"}",Expectation.ANSWER),
        Command(0,0x13,"indentify_device_lingo","${"device identification"}",Expectation.ANSWER),
        Command(0,0x1a,"get_ipod_authention_info","${"request authentication info (this firmware length 0x1a)"}",Expectation.ANSWER),
        Command(0,0x15,"ret_accessory_auth_info","${"report accessory authentication info (this firmware length 0x15)"}",Expectation.ACCEPT),
        Command(0,0x18,"ret_accessory_auth_signature","${"report accessory authentication signature"}",Expectation.ACCEPT),
        Command(0,0x38,"start_idps","${"identification phase start"}",Expectation.ACCEPT),
        Command(0,0x3b,"end_idps","${"identification phase end"}",Expectation.ACCEPT),
        // lingo 2：HID
        Command(2,0x0f,"ipod_hid_register_descriptor","${"HID descriptor register"}",Expectation.UNCONFIRMED),
        Command(2,0x10,"ipod_hid_report","${"HID report"}",Expectation.UNCONFIRMED),
        Command(2,0x12,"ipod_hid_unregister_descriptor","${"HID descriptor unregister"}",Expectation.UNCONFIRMED),
        // lingo 3：远程控制与设备信息
        Command(3,0x05,"enter_extended_interface_mode","${"enter extended interface mode"}",Expectation.ANSWER),
        Command(3,0x06,"exit_extended_interface_mode","${"exit extended interface mode"}",Expectation.ANSWER),
        Command(3,0x07,"ipod_get_model","${"request device model"}",Expectation.ANSWER),
        // lingo 4：曲库浏览
        Command(4,0x16,"ipod_reset_DB_section","${"library segment reset"}",Expectation.ANSWER),
        Command(4,0x17,"select_DB_record","${"select library record"}",Expectation.ANSWER),
        Command(4,0x1c,"ipod_get_play_status","${"request playback status"}",Expectation.ANSWER),
        // lingo 10：采样率
        Command(10,0x03,"ret_accessory_sample_rate","${"report sample rate"}",Expectation.ACCEPT)
    )

    fun find(lingo:Int,command:Int):Command?=commands.firstOrNull{it.lingo==lingo&&it.command==command}

    /** 未知组合返回 [Expectation.UNCONFIRMED]，让调用方无需分支就能走"记录后继续"这条路。 */
    fun expectation(lingo:Int,command:Int)=find(lingo,command)?.expectation?:Expectation.UNCONFIRMED

    /** lingo 编号 → 名称。catplay-cross-reference §4（第三方、由 Apple ATS 规格生成，B 级）：
     *  固件逆出的 5 个片段（0/2/3/4/10）**全部落在表内且用途吻合**（gap-closure §3.3），
     *  所以它只作识别与日志用，不改变任何行为。 */
    private val lingoNames=mapOf(
        0x00 to "General",0x01 to "Microphone(deprecated)",0x02 to "Simple Remote",0x03 to "Display Remote",
        0x04 to "Extended Interface",0x05 to "Accessory Power(deprecated)",0x06 to "USB Host Mode",0x07 to "RF Tuner",
        0x08 to "Accessory Equalizer",0x09 to "Sports",0x0a to "Digital Audio",0x0b to "Test",
        0x0c to "Storage",0x0d to "iPod Out",0x0e to "Location")
    fun name(lingo:Int)=lingoNames[lingo]?:"lingo$lingo"

    /** 本实现现在对一条 lingo 命令能做什么，以及**为什么没做**。
     *
     * 与 [Expectation] 分开：那个说"文档认为谁该应答"，这个说"我们现在到哪一步了"。
     * 之所以要有 [ANSWER_WITHOUT_EVIDENCE] 这一档，是因为文档里"需要应答"和"应答长什么样"
     * 是两件事：§3.6 记录的是配件侧的 `ipod_get_ipod_lingo_protocol_version` /
     * `option_for_lingo` 调用点，§3.2 明确 option 位含义"需抓包确认"，§10 只给了包**外壳**的布局。
     * 缺的是内容 —— 那就不能编造（README 的证据分级：缺口不可编码，须先补证据）。 */
    enum class Duty {
        /** 对端在索取信息，且应答载荷布局有证据。 */
        ANSWER,
        /** 对端在索取信息，但应答的载荷布局在文档里是缺口：记录后等待抓包，不猜。 */
        ANSWER_WITHOUT_EVIDENCE,
        /** 应答需要 MFi 认证芯片签名：没有芯片就完不成（carplay-client-spec §2.3「不可绕」）。 */
        ANSWER_REQUIRES_MFI_CHIP,
        /** 对端在通报信息，客户端接收。 */
        ACCEPT,
        /** 方向或职责未确认：只记录。 */
        UNCONFIRMED
    }

    fun duty(lingo:Int,command:Int):Duty {
        if(lingo==0&&command==0x1a)return Duty.ANSWER_REQUIRES_MFI_CHIP
        return when(expectation(lingo,command)){
            Expectation.ACCEPT->Duty.ACCEPT
            Expectation.UNCONFIRMED->Duty.UNCONFIRMED
            Expectation.ANSWER->Duty.ANSWER_WITHOUT_EVIDENCE
        }
    }
}
