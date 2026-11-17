package cn.musicpilgrimage.app.data

/** An immutable credential snapshot prevents an account switch from changing an in-flight request's actor. */
internal data class AuthRequestScope(val owner:String?,private val accessToken:String?) {
    fun authorization(expectedOwner:String?,observedOwner:String?):String? {
        requireUserAction(owner==expectedOwner&&observedOwner==expectedOwner){"账号已切换，请重新打开页面"}
        return if (owner == null) null else accessToken ?: throw UserActionException("登录已失效，请重新登录")
    }
}
