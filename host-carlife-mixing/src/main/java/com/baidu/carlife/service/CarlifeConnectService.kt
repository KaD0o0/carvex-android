package com.baidu.carlife.service
import android.app.*
import android.content.*
import android.os.*
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceDelegate
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceOwner
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceSession
import kotlinx.coroutines.*
import java.util.UUID

class CarlifeConnectService:Service() {
    private lateinit var delegate:MixingServiceDelegate
    private val controller:MixingServiceSession get()=delegate.session
    private val binder=Binder()
    private var disconnectJob:Job?=null
    private var sessionJob:Job?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onCreate(){
        super.onCreate()
        val owner=application as? MixingServiceOwner
            ?: error("Application must implement MixingServiceOwner")
        delegate=owner.createMixingServiceDelegate(this)
        activeInstance=this
    }
    override fun onBind(intent:Intent?):IBinder=binder // KeepAlive does not establish a session.
    /** 只记录宿主 extras 的 key 和类型，不记录值。 */
    @Suppress("DEPRECATION")
    private fun Intent.describeExtras():String {
        val extras=extras?:return ""
        return extras.keySet().filterNot{it=="internal_token"}.joinToString(","){key->key+"="+(extras.get(key)?.javaClass?.simpleName?:"null")}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        // 落在任何提前返回之前：被拒的请求也要留痕。
        delegate.log("entry","Intent extras: "+(intent?.describeExtras()?:"none"))
        val internal=intent?.getStringExtra("internal_token")==token(this)
        if(internal&&intent?.action==STOP){
            if(disconnectJob?.isActive!=true)disconnectJob=scope.launch{
                try{sessionJob?.cancelAndJoin();controller.disconnect()}
                catch(t:CancellationException){throw t}
                catch(t:Exception){delegate.log("entry","Local disconnect failed "+t.javaClass.simpleName+" "+(t.message?:""))}
                finally{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            }
            return START_NOT_STICKY
        }
        if(disconnectJob?.isActive==true){delegate.log("entry","Connection request ignored while disconnecting");return START_NOT_STICKY}
        val hostRequest = delegate.brand.acceptsHostIntent(
            intent?.getStringExtra("bindPackageName"),
            intent?.getStringExtra("bindClassName"),
            intent?.getStringExtra("manufacturer"),
            intent?.getIntExtra("usb_type", -1) ?: -1,
            intent?.getBooleanExtra("is_box", false) ?: false,
        )
        if(!((internal&&intent?.action==CONNECT)||hostRequest)){delegate.log("entry","Unsupported or invalid service entry");if(!controller.active&&sessionJob?.isActive!=true)stopSelf(startId);return START_NOT_STICKY}
        val missing=delegate.missingPermissions()
        if(missing.isNotEmpty()){delegate.log("entry","Required permission missing: "+missing.joinToString());stopSelf(startId);return START_NOT_STICKY}
        try{startForeground(1,notification("准备连接"))}catch(t:Exception){delegate.log("entry","Foreground start failed "+t.javaClass.simpleName);stopSelf(startId);return START_NOT_STICKY}
        if(!controller.active&&sessionJob?.isActive!=true)startSession(hostRequest)
        return START_NOT_STICKY
    }
    /** The application owns preparation and session implementation; the service owns their lifetime. */
    private fun startSession(hostRequest:Boolean){
        sessionJob=scope.launch{
            try {
                controller.start(hostRequest)
                controller.status.distinctUntilChanged().collect{status->
                    getSystemService(NotificationManager::class.java).notify(1,notification(status.text))
                    if(status.failed){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
                }
            }catch(t:CancellationException){throw t}
            catch(t:Exception){
                delegate.log("entry","Session start failed "+t.javaClass.simpleName+" "+(t.message?:""))
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
        }
    }
    private fun notification(text:String):Notification {
        val stop=PendingIntent.getService(this,1,Intent(this,CarlifeConnectService::class.java).setAction(STOP).putExtra("internal_token",token(this)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return delegate.notification(text,stop)
    }
    override fun onDestroy(){
        if(activeInstance===this)activeInstance=null
        scope.cancel()
        try{if(::delegate.isInitialized)controller.close()}finally{super.onDestroy()}
    }
    companion object {
        const val CONNECT="io.github.iamr0s.carvex.CONNECT";const val STOP="io.github.iamr0s.carvex.STOP"
        @Synchronized private fun token(c:Context):String {
            val p=c.getSharedPreferences("entry",Context.MODE_PRIVATE)
            return p.getString("token",null)?:UUID.randomUUID().toString().also{p.edit().putString("token",it).apply()}
        }
        fun connect(c:Context){c.startForegroundService(Intent(c,CarlifeConnectService::class.java).setAction(CONNECT).putExtra("internal_token",token(c)))}
        fun disconnect(c:Context){c.startService(Intent(c,CarlifeConnectService::class.java).setAction(STOP).putExtra("internal_token",token(c)))}
        @Volatile private var activeInstance:CarlifeConnectService?=null
        fun setVideoBitrate(value:Int){activeInstance?.controller?.setVideoBitrate(value)}
        /** 手动模拟触发一个 CarWith 键码（按键调节区的“模拟”按钮）。 */
        fun triggerCarWithKey(code:Int){activeInstance?.controller?.sendHostKey(code)}
        fun startCarWithAudioProbe(){
            activeInstance?.let{it.controller.startHostAudioProbe()}
        }
        fun toggleCarPlayAudioTest(){
            activeInstance?.let{it.controller.toggleProtocolAudioTest()}
        }
    }
}
