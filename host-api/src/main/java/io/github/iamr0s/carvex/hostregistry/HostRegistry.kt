package io.github.iamr0s.carvex.hostregistry

import android.content.Context
import io.github.iamr0s.carvex.sessioncore.CastHost
import io.github.iamr0s.carvex.sessioncore.HostCallbacks

/**
 * 一个内容宿主的插件描述。
 *
 * 只描述"怎么判断可用、怎么创建"；不包含任何协议知识，也不假定宿主类型。
 * 具体实现统一放在 `:host-carlife-mixing`，由 `:app` 注册。
 */
interface HostPlugin {
    /** 稳定标识，用于设置项与日志；不要随版本变化。 */
    val id: String

    /** 界面展示名。 */
    val displayName: String

    /** 该生态在本机是否可用（应用已安装、服务可绑定等）。实现不得抛异常。 */
    fun isAvailable(context: Context): Boolean

    /** 创建宿主实例；调用方保证 `isAvailable` 为真。 */
    fun create(context: Context, callbacks: HostCallbacks): CastHost
}

/**
 * 宿主插件注册表。
 *
 * 设计要点：
 *  1. 注册发生在 `:app` 启动时，注册表本身不依赖任何宿主模块；
 *  2. 「可用性探测」以函数参数注入，便于单测在不启动 Android 的情况下验证选择逻辑；
 *  3. 选择顺序 = 注册顺序，`select(id)` 才按标识精确匹配。
 */
object HostRegistry {
    private val registered=LinkedHashMap<String,HostPlugin>()

    @Synchronized
    fun install(vararg plugins: HostPlugin) {
        for (plugin in plugins) {
            require(plugin.id.isNotBlank()) { "host plugin id must not be blank" }
            registered[plugin.id]=plugin
        }
    }

    @Synchronized
    fun installed(): List<HostPlugin> = registered.values.toList()

    @Synchronized
    fun byId(id: String?): HostPlugin? = id?.let { registered[it] }

    /** 按给定探测器过滤出可用插件；探测异常视为不可用。 */
    fun available(probe: (HostPlugin) -> Boolean): List<HostPlugin> =
        installed().filter { runCatching { probe(it) }.getOrDefault(false) }

    /** 选中一个插件：优先按 id 精确匹配，否则取第一个可用的。 */
    fun select(id: String?, probe: (HostPlugin) -> Boolean): HostPlugin? {
        val exact=byId(id)
        if (exact != null && runCatching { probe(exact) }.getOrDefault(false)) return exact
        return available(probe).firstOrNull()
    }

    fun select(id: String?, context: Context): HostPlugin? = select(id) { it.isAvailable(context) }

    fun selectDefault(context: Context): HostPlugin? = select(null, context)

    @Synchronized
    fun clear() = registered.clear()
}
