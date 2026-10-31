package com.baidu.carlife.mix.xiaomi
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import io.github.iamr0s.carvex.Permissions
import io.github.iamr0s.carvex.sessioncore.Appearance
import io.github.iamr0s.carvex.sessioncore.BridgeStore

/** 连接前的授权入口，只做申请本身。
 *
 * 页面里没有任何内容，只铺一层和主页相同的背景。透明是行不通的：透明会让底下的页面直接透出来，
 * 看上去就是一块飘着的板。系统权限弹窗必须由一个 Activity 发起，这个 Activity 的意义只是那个
 * 发起者，所以它不该有界面，但也不该是透明的。
 *
 * 逐个申请而不是一次性提交整组：一次性弹窗只给一个"允许/拒绝"，用户看不到自己到底在授什么；
 * 逐个来则每项都有自己的说明，同意或拒绝都会继续下一项，全部走完后由 [Permissions.granted]
 * 统一判定。 */
class XiaomiPermissionActivity:ComponentActivity(){
    private val required=Permissions.requested()
    /** 下一次要申请的权限下标。 */
    private var index=0
    private val request:ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(!granted)required.getOrNull(index)?.let{BridgeStore.log("entry","Permission denied: "+it)}
        index++
        if(index<required.size)request.launch(required[index])else finishResult()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(Permissions.granted(this)){finishResult();return}
        // 背景跟着用户自己的主题设置走，和主页是同一层。
        setContent{
            val o by BridgeStore.options.collectAsState()
            val dark=when(o.appearance){Appearance.SYSTEM->isSystemInDarkTheme();Appearance.DARK->true;Appearance.LIGHT->false}
            PermissionBackground(dark,PALETTE.getOrElse(o.color){PALETTE[0]})
        }
        // onCreate 里直接 launch 会赶在生命周期就绪之前，交给主线程队列的第一帧之后再开始。
        window.decorView.post{ask()}
    }
    /** 从断点继续申请；已经没有缺项时直接回结果。 */
    private fun ask(){
        if(index>=required.size){finishResult();return}
        request.launch(required[index])
    }
    private fun finishResult(){setResult(if(Permissions.granted(this))Activity.RESULT_OK else Activity.RESULT_CANCELED);finish()}
}

/** 与主页同一套主题色。这里自带一份，是为了不把 MainActivity 里的私有实现改成公开的。 */
private val PALETTE=listOf(Color(0xff7c9dff),Color(0xffbd8bff),Color(0xff43cdb7),
    Color(0xfff78db6),Color(0xffffbb64),Color(0xff71c6f1),Color(0xff8ace75),Color(0xffff9478),Color(0xffa8b6c9))

/** 主页那层背景的同款画法：底色渐变 + 一块斜向色斑 + 一条弧线。 */
@Composable private fun PermissionBackground(dark:Boolean,accent:Color){
    Canvas(Modifier.fillMaxSize()){
        drawRect(Brush.linearGradient(if(dark)listOf(lerp(accent,Color(0xff070b14),.93f),lerp(accent,Color(0xff151d2b),.72f),lerp(accent,Color(0xff0b141b),.87f))
            else listOf(lerp(accent,Color.White,.9f),lerp(accent,Color.White,.64f),lerp(accent,Color.White,.94f))))
        val path=Path().apply{moveTo(-size.width*.3f,size.height*.9f);cubicTo(size.width*.2f,-size.height*.4f,size.width*.6f,size.height*1.5f,size.width*1.3f,size.height*.1f);lineTo(size.width*1.2f,size.height);lineTo(0f,size.height);close()}
        drawPath(path,Brush.linearGradient(listOf(accent.copy(alpha=if(dark).24f else .22f),Color.Transparent,accent.copy(alpha=.05f))))
        val line=Path().apply{moveTo(-size.width*.1f,size.height*.65f);cubicTo(size.width*.25f,-size.height*.2f,size.width*.6f,size.height*1.25f,size.width*1.1f,size.height*.05f)}
        drawPath(line,accent.copy(alpha=.18f),style=Stroke(size.width*.08f))
    }
}
