package io.github.iamr0s.carvex.sessioncore

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 历史监听配置：由多个 CarPlay 事件组成的触发记录及其动作说明。
 *  运行时协议转发不再读取这些动作；它们保留给诊断和兼容已有设置数据。 */
data class BindingEvent(val trigger:List<String>,val actions:List<Int>,
                        val context:String=CarPlayEvents.CONTEXT_ANY)

/** 内部事件的持久存储。写在 SharedPreferences 里，重启后依旧有效。 */
class KeyBindings private constructor(private val prefs:SharedPreferences) {
    private val state=MutableStateFlow(load())

    /** 全部历史记录，按界面上显示的顺序。 */
    val events:StateFlow<List<BindingEvent>> = state.asStateFlow()

    fun current():List<BindingEvent> = state.value

    fun replaceAll(list:List<BindingEvent>) {
        val next=list.filter{it.trigger.isNotEmpty()}
        state.value=next
        val text=next.joinToString("\n") { event ->
            event.trigger.joinToString(",") + "|" + event.actions.joinToString(",") + "|" +
                event.context
        }
        prefs.edit().putString(EVENTS,text).apply()
    }
    fun add(event:BindingEvent) = replaceAll(state.value+event)
    fun removeAt(index:Int) = replaceAll(state.value.filterIndexed{i,_->i!=index})
    fun updateAt(index:Int,event:BindingEvent) =
        replaceAll(state.value.mapIndexed{i,e->if(i==index)event else e})

    private fun load():List<BindingEvent> =
        prefs.getString(EVENTS,"").orEmpty().split("\n").mapNotNull{line->
            if(line.isBlank())null else {
                val parts=line.split("|")
                val trigger=parts.getOrNull(0).orEmpty().split(",").map{it.trim()}.filter{it.isNotEmpty()}
                val actions=parts.getOrNull(1).orEmpty().split(",").mapNotNull{it.trim().toIntOrNull()}
                val context=parts.getOrNull(2)?.trim().orEmpty().ifEmpty{CarPlayEvents.CONTEXT_ANY}
                trigger.takeIf{it.isNotEmpty()}?.let{BindingEvent(it,actions,context)}
            }
        }

    companion object {
        private const val NAME="key_bindings"
        private const val EVENTS="events"
        fun open(context:Context):KeyBindings =
            KeyBindings(context.applicationContext.getSharedPreferences(NAME,Context.MODE_PRIVATE))
    }
}
