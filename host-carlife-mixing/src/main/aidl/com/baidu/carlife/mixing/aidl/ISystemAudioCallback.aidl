// ISystemAudioCallback.aidl
package com.baidu.carlife.mixing.aidl;
interface ISystemAudioCallback {
    void onAudioStreamReady(in byte[] pcm, int category, int sampleRate, int channels, int encoding);
    void onAudioStateChange(int category, int state);
}
