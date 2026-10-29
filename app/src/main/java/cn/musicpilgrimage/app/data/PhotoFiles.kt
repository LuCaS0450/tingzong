package cn.musicpilgrimage.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

class PhotoFiles(private val context: Context) {
    private fun ownerPrefix(owner:String)=java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString(""){"%02x".format(it)}+"-"
    fun draftId(owner:String)=ownerPrefix(owner)+UUID.randomUUID().toString()
    fun deleteOwner(owner:String) {
        require(owner.isNotBlank())
        File(context.filesDir,"photos").listFiles()?.filter{it.isFile && it.name.startsWith(ownerPrefix(owner))}?.forEach{delete(it.path)}
    }
    fun cameraUri(): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        // Old abandoned camera captures are disposable; never delete a pending check-in photo.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "${UUID.randomUUID()}.jpg"))
    }
    fun import(uri: Uri, id: String): Pair<String, String> {
        val temporary = File(context.cacheDir, "$id-import")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取这张照片，请重新选择" }
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192); var size = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= 25L * 1024 * 1024) { "照片过大，请选择小于 25 MB 的图片" }; output.write(buffer, 0, n) }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片格式" }
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 3200) options.inSampleSize *= 2
            var bitmap = requireNotNull(BitmapFactory.decodeFile(temporary.path, options)) { "无法打开照片" }
            val exif = ExifInterface(temporary)
            val transform = Matrix().apply {
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                }
            }
            if (!transform.isIdentity) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also { if (it != bitmap) bitmap.recycle() }
            bitmap = scale(bitmap, 1600)
            var encoded: ByteArray
            var quality = 85
            while (true) {
                encoded = ByteArrayOutputStream().use { bytes -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes); bytes.toByteArray() }
                if (encoded.size <= 400 * 1024) break
                if (quality > 45) quality -= 10 else { bitmap = scale(bitmap, (maxOf(bitmap.width, bitmap.height) * .75).roundToInt()); quality = 75 }
            }
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val photo = File(dir, "$id.jpg").apply { writeBytes(encoded) }
            val small = scale(bitmap, 480)
            val thumbnail = File(dir, "$id-thumb.jpg").apply { outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) } }
            small.recycle()
            return photo.path to thumbnail.path
        } finally { temporary.delete() }
    }
    private fun scale(bitmap: Bitmap, maximum: Int): Bitmap {
        val ratio = maximum.toDouble() / maxOf(bitmap.width, bitmap.height)
        if (ratio >= 1) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt().coerceAtLeast(1), (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true).also { if (it != bitmap) bitmap.recycle() }
    }
    fun delete(path: String?) {
        if (path == null) return
        val file = File(path).canonicalFile
        if (file.parentFile == File(context.filesDir, "photos").canonicalFile) file.delete()
    }
}
