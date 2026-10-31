package io.github.iamr0s.carvex
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baidu.carlife.mixing.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParcelContractTest {
    @Test fun castFieldOrder(){
        val p=Parcel.obtain()
        try{CastConfig(1280,720,160,null).writeToParcel(p,0);p.setDataPosition(0);assertEquals(1280,p.readInt());assertEquals(720,p.readInt());assertEquals(160,p.readInt());assertNull(p.readParcelable<android.view.Surface>(javaClass.classLoader))}
        finally{p.recycle()}
    }
    @Test fun musicWireOrder(){
        val p=Parcel.obtain()
        try{MusicInfo("source","song","artist",123L,4,2).writeToParcel(p,0);p.setDataPosition(0);assertEquals("source",p.readString());assertEquals("song",p.readString());assertEquals("artist",p.readString());assertEquals(123L,p.readLong());assertEquals(4,p.readInt());assertEquals(2,p.readInt())}
        finally{p.recycle()}
    }
}
