package cn.musicpilgrimage.app.data

internal class UserActionException(message: String) : IllegalArgumentException(message)

internal inline fun requireUserAction(condition: Boolean, message: () -> String) {
    if (!condition) throw UserActionException(message())
}
