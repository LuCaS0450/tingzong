package cn.musicpilgrimage.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Small metadata-free JPEG for the authenticated user's own account metadata. */
internal object AccountAvatar {
    fun import(context:Context,photos:PhotoFiles,uri:Uri,owner:String):String {
        val (photo,thumbnail)=photos.import(uri,photos.draftId(owner))
        try {
            val original=requireNotNull(BitmapFactory.decodeFile(thumbnail)){"无法读取头像，请重新选择"}
            val ratio=128f/maxOf(original.width,original.height)
            val small=Bitmap.createScaledBitmap(original,(original.width*ratio).roundToInt().coerceAtLeast(1),(original.height*ratio).roundToInt().coerceAtLeast(1),true)
            val bytes=ByteArrayOutputStream().use{out->small.compress(Bitmap.CompressFormat.JPEG,78,out);out.toByteArray()}
            if(small!==original)small.recycle();original.recycle()
            require(bytes.size<=23_000){"头像过大，请换一张图片"}
            return "data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)
        } finally {photos.delete(photo);photos.delete(thumbnail)}
    }
    fun bitmap(value:String?)=value?.takeIf{it.length<=32_000&&it.startsWith("data:image/jpeg;base64,")}?.let {
        runCatching{Base64.decode(it.substringAfter(','),Base64.NO_WRAP).let{bytes->
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..512&&bounds.outHeight in 1..512)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        }}.getOrNull()
    }
}
