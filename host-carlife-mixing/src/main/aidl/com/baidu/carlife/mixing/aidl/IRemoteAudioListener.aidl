// IRemoteAudioListener.aidl
package com.baidu.carlife.mixing.aidl;
interface IRemoteAudioListener {
    void onReceiveAudioStream(in byte[] pcm, int sampleRate, int channels, int encoding);
}
