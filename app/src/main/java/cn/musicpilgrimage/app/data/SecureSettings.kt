package cn.musicpilgrimage.app.data
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettings(context:Context):SessionManager,CodeVerifierCache {
 private val prefs=context.getSharedPreferences("v2-secure",Context.MODE_PRIVATE)
 private val codec=Json { ignoreUnknownKeys=true }
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (store.getKey("pilgrimage-v2",null) as? SecretKey)?.let { return it }
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("pilgrimage-v2",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 @android.annotation.SuppressLint("ApplySharedPref")
 @Synchronized private fun write(name:String,value:String?) {
  // Complete durable writes before reporting auth success or logout.
  if(value==null) { check(prefs.edit().remove(name).commit());return }
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
  val bytes=cipher.doFinal(value.toByteArray(Charsets.UTF_8))
  check(prefs.edit().putString(name,Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(bytes,Base64.NO_WRAP)).commit())
 }
 @Synchronized private fun read(name:String):String? = runCatching {
  val parts=prefs.getString(name,null)?.split(':') ?: return null
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP))) }
  String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
 }.getOrNull()
 override suspend fun saveSession(session:UserSession)=withContext(Dispatchers.IO){write("session",codec.encodeToString(session))}
 override suspend fun loadSession():UserSession? = withContext(Dispatchers.IO){read("session")?.let { runCatching { codec.decodeFromString<UserSession>(it) }.getOrNull() }}
 override suspend fun deleteSession()=withContext(Dispatchers.IO){write("session",null)}
 override suspend fun saveCodeVerifier(codeVerifier:String)=withContext(Dispatchers.IO){write("pkce",codeVerifier)}
 override suspend fun loadCodeVerifier():String? = withContext(Dispatchers.IO){read("pkce")}
 override suspend fun deleteCodeVerifier()=withContext(Dispatchers.IO){write("pkce",null)}
}
