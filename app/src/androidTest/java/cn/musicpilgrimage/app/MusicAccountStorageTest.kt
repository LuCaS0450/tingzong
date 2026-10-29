package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.AccountAvatar
import cn.musicpilgrimage.app.data.PhotoFiles
import cn.musicpilgrimage.app.design.AppPreferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.util.UUID

class MusicAccountStorageTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun twoSwitchWritesPreserveEachOthersState()=runBlocking {
        val prefs=AppPreferences(context);val original=prefs.read()
        try {
            awaitAll(async(Dispatchers.IO){prefs.setMuted(!original.muted)},async(Dispatchers.IO){prefs.setReducedMotion(!original.reducedMotion)})
            val stored=AppPreferences(context).read()
            assertEquals(!original.muted,stored.muted);assertEquals(!original.reducedMotion,stored.reducedMotion)
        }finally{prefs.set(original)}
    }
    @Test fun avatarIsSmallReencodedAndTemporaryImportsAreRemoved(){
        val source=File(context.cacheDir,"avatar-layout-${UUID.randomUUID()}.jpg")
        val photoDir=File(context.filesDir,"photos")
        val previous=photoDir.listFiles()?.map{it.name}?.toSet().orEmpty()
        try {
            Bitmap.createBitmap(64,96,Bitmap.Config.ARGB_8888).let{bitmap->source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,80,it)};bitmap.recycle()}
            ExifInterface(source).apply{setAttribute(ExifInterface.TAG_GPS_LATITUDE,"1/1,2/1,3/1");setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N");saveAttributes()}
            val value=AccountAvatar.import(context,PhotoFiles(context),Uri.fromFile(source),"isolated-avatar-test")
            assertTrue(value.startsWith("data:image/jpeg;base64,"));assertTrue(value.length<=32_000)
            val savedExif=ExifInterface(ByteArrayInputStream(Base64.decode(value.substringAfter(','),Base64.NO_WRAP)))
            assertNull(savedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(savedExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
            val bitmap=AccountAvatar.bitmap(value)
            assertNotNull(bitmap);assertTrue(maxOf(bitmap!!.width,bitmap.height)<=128);bitmap.recycle()
            assertEquals(previous,photoDir.listFiles()?.map{it.name}?.toSet().orEmpty())
            assertNull(AccountAvatar.bitmap("data:image/jpeg;base64,invalid"))
            assertNull(AccountAvatar.bitmap("https://untrusted.example/avatar"))
        }finally{source.delete()}
    }
}
