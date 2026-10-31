package io.github.iamr0s.carvex.sessioncore

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本应用自己的**连接历史**：只记录"我们真的连过"的车机。
 *
 * 与系统配对列表（[BondedDevices]）是两件事，首页两段各显示一部分：
 *  · 这里 = 有证据的成功连接，带最近时间，用来一键回连；
 *  · 系统配对列表 = 用户/系统层已配对，但本应用可能从未连过。
 *
 * 两段**必须互不重复**，去重规则见 [partition]。
 */
object DeviceHistory {
    data class Entry(val name: String, val address: String, val lastConnectedAt: Long) {
        /** 地址是唯一键：蓝牙名可改，地址不会。 */
        val key: String get() = address.uppercase()
    }

    private const val PREFS = "device-history"
    private const val KEY = "entries"
    /** 只留最近这些条：历史是"回连入口"，不是审计日志。 */
    private const val MAX = 20

    private val state = MutableStateFlow<List<Entry>>(emptyList())
    /** 最近优先（按 lastConnectedAt 降序）。 */
    val entries: StateFlow<List<Entry>> = state.asStateFlow()

    private lateinit var prefs: SharedPreferences

    @Synchronized fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = decode(prefs.getString(KEY, null))
    }

    /** 一次成功连接后调用。同地址视为同一台车机：更新时间并提到最前，不产生重复行。 */
    @Synchronized fun record(name: String, address: String, at: Long = System.currentTimeMillis()) {
        if (!::prefs.isInitialized) return
        val key = address.uppercase()
        if (key.isBlank()) return
        val trimmed = name.trim().ifBlank { address }
        val next = (listOf(Entry(trimmed, key, at)) + state.value.filterNot { it.key == key })
            .take(MAX)
        state.value = next
        prefs.edit().putString(KEY, encode(next)).apply()
    }

    @Synchronized fun remove(address: String) {
        if (!::prefs.isInitialized) return
        val key = address.uppercase()
        val next = state.value.filterNot { it.key == key }
        if (next.size == state.value.size) return
        state.value = next
        prefs.edit().putString(KEY, encode(next)).apply()
    }

    @Synchronized fun clear() {
        if (!::prefs.isInitialized) return
        state.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    /**
     * 把「历史」与「系统已配对」切成首页要的两段，**并保证不重复**。
     *
     * 去重规则（有意选择，不是随意为之）：
     *  历史里出现过的地址，一律**不再**出现在系统配对段 —— 用户已经能从上面那行连过，
     *  在下面重复一次只会让人以为是两台不同的设备。
     *  `paired` 里与历史同地址但名字不同的，以**历史里的名字**为准（那是我们实际连上时看到的）。
     */
    fun partition(
        history: List<Entry>,
        paired: List<Pair<String, String>>,
        normalize: (String) -> String = { it.uppercase() }
    ): Pair<List<Entry>, List<Pair<String, String>>> {
        val known = history.mapTo(HashSet()) { normalize(it.address) }
        val rest = paired.filterNot { normalize(it.second) in known }
        return history to rest
    }

    private fun encode(list: List<Entry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("name", e.name); put("address", e.address); put("at", e.lastConnectedAt)
            })
        }
    }.toString()

    private fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        // 损坏的偏好设置不应该让首页崩掉：解析不出就当作空历史。
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                // 地址在这里也要 uppercase（不只是 record 的时候）。
                // 理由同 BridgeStore.configure：checkBluetoothAddress 大小写敏感，
                // 而 options.target 是从这里选出来直接写进去的。
                // 老版本可能存过小写地址，读的时候顺手归一化等于做一次静默迁移。
                val address = o.optString("address").takeIf { it.isNotBlank() }
                    ?.uppercase() ?: return@mapNotNull null
                Entry(o.optString("name").ifBlank { address }, address, o.optLong("at"))
            }.sortedByDescending { it.lastConnectedAt }.take(MAX)
        }.getOrDefault(emptyList())
    }
}
