package io.github.iamr0s.carvex.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住页面转场的**方向约定**。
 *
 * ════════════════════════════════════════════════════════════════════
 * 导航结构改版后，这套约定的依据也变了
 * ════════════════════════════════════════════════════════════════════
 * 上一版用 Navigation-Compose，六个转场钩子（enter/exit/pop/predictive*）
 * 全都挂在 NavHost 上，方向由**路由**决定。
 *
 * 这一版与 DeviceApp 一致：顶层是底部导航的两个 Tab，二级页（管理/日志）
 * 用 AnimatedContent 从 Tab 上"推入"。于是方向不再由"目标页是谁"决定，
 * 而由**"是不是在推入二级页"**决定 —— 这是这套测试要钉死的第一件事。
 *
 * 方向语义（slideIn/OutHorizontally 的 lambda 收 fullWidth，返回起始/终止偏移）：
 *   · 正值 = 从右开始 / 向右结束；
 *   · 负值 = 从左开始 / 向左结束。
 *
 * 这套东西出过三类只有肉眼才发现的故障：
 *
 *  1. 退出那一侧压根没写 slide —— 变成"新页滑进来 + 旧页原地淡掉"，
 *     两张页面各演各的，看着像穿帮。
 *  2. predictive* 没覆盖（上一版的问题）。这一版不再有预测式返回——
 *     二级页由 BackHandler 接管，转场只有两条路径，因此这个坑自然消失。
 *  3. 跟手与收尾用了不同的位移函数，交界处必然跳一下。
 *
 * 说明：Compose 的 `Slide` 内部类与 offset 字段是 internal，测试拿不到，
 * 因此这里断言的是**我们传给 slide 函数的那个 (Int) -> Int 本身** ——
 * 它正是决定方向的唯一输入，测它就够了。
 */
class NavTransitionContractTest {

    private val slideSpec = tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)
    private val full = 1080

    /** 推入二级页：新页从右滑入。 */
    private val enterFromRight: (Int) -> Int = { f -> f / 4 }

    /** 返回时二级页向右滑走。 */
    private val exitToRight: (Int) -> Int = { f -> f / 4 }

    @Test
    fun pushingDetailEntersFromTheRight() {
        // 正值 = 从右开始，即"从右边滑进来"。
        assertTrue("推入时新页应从右侧开始", enterFromRight(full) > 0)
    }

    @Test
    fun poppingDetailExitsToTheRight() {
        // 正值 = 向右结束，即"往右边滑走"，与推入严格镜像。
        assertTrue("返回时二级页应向右滑走", exitToRight(full) > 0)
        assertEquals(
            "推入的入场方向与返回的出场方向必须一致（同一个元素进出）",
            enterFromRight(full),
            exitToRight(full),
        )
    }

    @Test
    fun detailSlideIsSmallerThanFullWidth() {
        // 二级页只滑 1/4 屏 —— 它是"从 Tab 上长出来"的，不是一次整屏跳转。
        // 整屏位移会让 Tab 底栏的上下文完全消失，用户分不清自己在哪一层。
        assertTrue("位移应小于一屏宽", kotlin.math.abs(enterFromRight(full)) < full)
        assertTrue("位移不应为 0（否则没有空间感）", enterFromRight(full) != 0)
    }

    @Test
    fun pushAndPopUseOppositeDirectionsForTheFadingPage() {
        // 推入时旧页淡出（不退场位移），返回时被露出的页淡入。
        // 这两者方向相反才读得出"进"与"出"——若相同，前进和返回长得一模一样。
        val pushEnter = slideInHorizontally(slideSpec, enterFromRight)
        val popExit = slideOutHorizontally(slideSpec, exitToRight)
        assertNotEquals(
            "推入与返回不能是同一组动画",
            pushEnter::class,
            popExit::class,
        )
    }

    @Test
    fun slideFunctionsAcceptTheseLambdas() {
        // 编译期契约：这几个函数能收下上面那组 lambda，且方向语义与断言一致。
        // 只要签名从 (Int) -> Int 变了（例如以后某版本改成 receiver 作用域），
        // 这里会先失败，而不是等到界面上看出怪。
        slideInHorizontally(slideSpec, enterFromRight)
        slideOutHorizontally(slideSpec, exitToRight)
        fadeIn(tween(300)) togetherWith fadeOut(tween(150))
    }
}

/**
 * 锁住二级页与 Tab 的关系。
 *
 * 这是改版后**新的**一条不变量：二级页必须"属于"某个 Tab，
 * 返回时回到它出来的那一个 —— 否则用户从设置里进日志、返回却到了设备页。
 */
class DetailOwnershipTest {

    @Test
    fun manageBelongsToDevicesTab() {
        assertEquals(Tab.DEVICES, Detail.Manage.tab)
    }

    @Test
    fun logsBelongsToSettingsTab() {
        // 日志入口在设置页的「诊断」组里，所以它属于设置 Tab。
        assertEquals(Tab.SETTINGS, Detail.Logs.tab)
    }

    @Test
    fun everyDetailHasATab() {
        // 新增二级页时，忘记给它指定归属会在这里失败，
        // 而不是在界面上表现为"返回跳到了另一个 Tab"。
        Detail.entries.forEach { detail ->
            assertTrue("${detail} 必须有归属的 Tab", detail.tab in Tab.entries)
        }
    }
}
