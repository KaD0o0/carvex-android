package io.github.iamr0s.carvex.carplay.core

/**
 * 媒体库（feature 13）的订阅状态与解析（第 17 章 §2.5–§2.9）。
 *
 * `4c00` 开始媒体库信息、`4c02` 停止；`4c03` 开始媒体库更新、`4c05` 停止。
 * 本实现没有媒体库数据源，所以订阅成功后只回一条**空库**的 `4c01`（文档允许"空库也发一条空消息"），
 * 点播请求（`4c06`–`4c09`）只解析并记录，不执行。
 */
object MediaLibrary {
    /** 组 2 合法子参数：0x00–0x13、0x15、0x17。 */
    private val MEDIA_ITEM_PROPERTIES = (0..0x13).toSet() + setOf(0x15, 0x17)
    /** 组 3：0,1,2,3,4,6,8 是属性位；5 与 7 是特例选项。 */
    private val PLAYLIST_PROPERTIES = setOf(0, 1, 2, 3, 4, 6, 8)
    /** 组 7 合法子参数：0,1,6,0x0c,0x0e,0x10,0x12。 */
    private val PLAYLIST_CONTENT_PROPERTIES = setOf(0, 1, 6, 0x0c, 0x0e, 0x10, 0x12)

    data class Subscription(
        val libraryUid: String,
        val lastRevision: String?,
        val mediaItemProperties: Set<Int>,
        val playlistProperties: Set<Int>,
        val playlistContentProperties: Set<Int>,
        val playlistOptions: Int,
        val flags: Int,
    )

    /** 已在 4c01 通告过的库；空库时没有可用 uid（4c03/4c05 必须按固件拒绝）。 */
    private val libraries = mutableSetOf<String>()

    @Volatile var infoActive: Boolean = false; private set
    @Volatile var updatesActive: Boolean = false; private set
    @Volatile var subscription: Subscription? = null; private set

    /** `4c00`：无参数，收到即置位。 */
    fun startInfo() {
        infoActive = true
    }

    /** 4c01 通告一个库时登记；本实现通告空库，因此默认没有任何可用 uid。 */
    fun advertise(uid: String) {
        libraries.add(uid)
    }

    /** `4c02`：无参数，收到即清零（固件不调平台 stop）。 */
    fun stopInfo() {
        infoActive = false
    }

    /**
     * `4c03`：解析订阅。返回 null 表示按固件应当**整条拒绝**：
     * `libraryUID` 缺失、字符串/组解析失败、组内子参数非零长度。
     */
    fun startUpdates(fields: IapFields): Subscription? {
        var uid: String? = null
        var revision: String? = null
        val items = mutableSetOf<Int>()
        val playlists = mutableSetOf<Int>()
        val contents = mutableSetOf<Int>()
        var playlistOptions = 0
        var flags = 0

        fun text(payload: ByteArray): String? =
            runCatching { payload.toString(Charsets.UTF_8).trimEnd('\u0000') }.getOrNull()

        for ((id, payload) in fields.ordered) {
            when (id) {
                0 -> uid = text(payload) ?: return null
                1 -> revision = text(payload) ?: return null
                2, 3, 7 -> {
                    // 组参数：正文是子参数序列，每个子参数必须零长度（由 IapFields 的调用方已拆好，
                    // 这里只按"子参数 id + 是否带载荷"校验）。
                    val children = runCatching { IapWire.parseFieldList(payload) }.getOrNull() ?: return null
                    for ((childId, childPayload) in children.ordered) {
                        if (childPayload.isNotEmpty()) return null
                        when (id) {
                            2 -> if (childId in MEDIA_ITEM_PROPERTIES) items += childId
                            3 -> if (childId in PLAYLIST_PROPERTIES) playlists += childId
                                else if (childId == 5) playlistOptions = playlistOptions or 1
                                else if (childId == 7) playlistOptions = playlistOptions or 2
                            7 -> if (childId in PLAYLIST_CONTENT_PROPERTIES) contents += childId
                        }
                    }
                }
                4, 5, 6 -> {
                    if (payload.isNotEmpty()) return null
                    flags = flags or (1 shl (id - 4))
                }
                else -> Unit
            }
        }
        val library = uid ?: return null
        if (library !in libraries) return null
        val parsed = Subscription(library, revision, items, playlists, contents, playlistOptions, flags)
        subscription = parsed
        updatesActive = true
        return parsed
    }

    /** `4c06`：参数 0 为 libraryUID；没有"库必须已激活"检查。 */
    fun playCurrentSelection(fields: IapFields): Boolean {
        val uid = fields[0]?.let { textOf(it) } ?: return false
        return uid.isNotEmpty()
    }

    /** `4c07`：itemList 长度必须是 8 的倍数且非空，libraryUID 必须有值。 */
    fun playItems(fields: IapFields): Boolean {
        val items = fields[0] ?: return false
        if (items.isEmpty() || items.size % 8 != 0) return false
        val uid = fields[2]?.let { textOf(it) } ?: return false
        return uid.isNotEmpty()
    }

    /** `4c08`：collectionID != 0，type != 8（未提供哨兵），libraryUID 必须有值。 */
    fun playCollection(fields: IapFields): Boolean {
        val id = fields[0]?.takeIf { it.size == 8 } ?: return false
        if (id.all { it == 0.toByte() }) return false
        val type = fields[1]?.takeIf { it.size == 1 } ?: return false
        if ((type[0].toInt() and 255) == 8) return false
        val uid = fields[3]?.let { textOf(it) } ?: return false
        return uid.isNotEmpty()
    }

    /** `4c09`：参数 0 必须是 libraryUID（非空），参数 1 必须是 void。 */
    fun playAllSongs(fields: IapFields): Boolean {
        val uid = fields[0]?.let { textOf(it) } ?: return false
        if (uid.isEmpty()) return false
        return fields[1]?.isEmpty() == true
    }

    private fun textOf(payload: ByteArray): String? =
        runCatching { payload.toString(Charsets.UTF_8).trimEnd('\u0000') }.getOrNull()

    /** `4c05`：必须带参数 0 libraryUID；库不在已通告表内时按固件拒绝、不改状态。 */
    fun stopUpdates(fields: IapFields): Boolean {
        val uid = fields[0]?.let { runCatching { it.toString(Charsets.UTF_8).trimEnd('\u0000') }.getOrNull() }
        if (uid == null || uid !in libraries) return false
        updatesActive = false
        subscription = null
        return true
    }
}
