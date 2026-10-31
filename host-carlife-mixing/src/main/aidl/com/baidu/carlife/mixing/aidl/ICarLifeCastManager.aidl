// ICarLifeCastManager.aidl
package com.baidu.carlife.mixing.aidl;
import android.content.Intent;
import android.os.Bundle;
import com.baidu.carlife.mixing.CastConfig;
import com.baidu.carlife.mixing.aidl.ISystemAudioCallback;
import com.baidu.carlife.mixing.aidl.ICarLifeCastCallback;
interface ICarLifeCastManager {
    void prepareCast(in CastConfig config);
    void stopCast();
    void setSystemAudioCallback(ISystemAudioCallback callback);
    void setCastCallback(ICarLifeCastCallback callback);
    Bundle invokeMethod(in Intent intent);
}
