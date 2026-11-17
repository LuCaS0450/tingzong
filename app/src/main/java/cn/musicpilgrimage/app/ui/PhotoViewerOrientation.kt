package cn.musicpilgrimage.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun rememberPhotoViewerOrientation(): PhotoViewerOrientation? {
    val context = LocalContext.current
    val activity = remember(context) { context.photoViewerActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(activity) {
        activity?.takeIf {
            val info = it.packageManager.getActivityInfo(it.componentName, 0)
            info.configChanges and ActivityInfo.CONFIG_ORIENTATION != 0
        }?.let(::PhotoViewerOrientation)
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller?.releaseAfterClose()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller?.release()
        }
    }
    return controller
}

private tailrec fun Context.photoViewerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.photoViewerActivity()
    else -> null
}

internal class PhotoViewerOrientation(private val activity: Activity) {
    private var previousRequest: Int? = null
    private var openingAxis: Int? = null
    private var physicalAxis: Int? = null
    private var manualBaseline: Int? = null
    private var returnHold: Int? = null

    private val listener = object : OrientationEventListener(activity.applicationContext) {
        override fun onOrientationChanged(orientation: Int) {
            val axis = when (orientation) {
                in 0..25, in 155..205, in 335..359 -> Configuration.ORIENTATION_PORTRAIT
                in 65..115, in 245..295 -> Configuration.ORIENTATION_LANDSCAPE
                else -> return
            }
            if (axis == physicalAxis) return
            physicalAxis = axis
            if (returnHold == axis) {
                releaseAfterClose()
            } else if (openingAxis != null && manualBaseline?.let { it != axis } == true && autoRotate) {
                manualBaseline = null
                previousRequest?.let { activity.requestedOrientation = it }
            }
        }
    }

    private val autoRotate: Boolean
        get() = Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0

    fun canRequest(config: Configuration): Boolean =
        !activity.isInMultiWindowMode &&
            (Build.VERSION.SDK_INT < 36 || config.smallestScreenWidthDp < 600)

    fun open(axis: Int) {
        if (previousRequest == null) previousRequest = activity.requestedOrientation
        if (returnHold != null) manualBaseline = physicalAxis ?: axis
        returnHold = null
        openingAxis = axis
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun toggle(current: Int) {
        if (!canRequest(activity.resources.configuration)) return
        manualBaseline = physicalAxis ?: current
        val target = if (current == Configuration.ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_PORTRAIT
        } else Configuration.ORIENTATION_LANDSCAPE
        requestAxis(target)
    }

    fun restoreOpeningAxis(): Int? {
        val axis = openingAxis ?: return null
        val config = activity.resources.configuration
        if (!canRequest(config)) return null
        val needsRotation = config.orientation != axis
        if (needsRotation || activity.requestedOrientation != previousRequest) requestAxis(axis)
        manualBaseline = null
        return axis.takeIf { needsRotation }
    }

    fun close() {
        val axis = openingAxis ?: return
        restoreOpeningAxis()
        openingAxis = null
        // Keep the archive at its opening orientation if the user still holds the phone sideways.
        if (canRequest(activity.resources.configuration) && autoRotate &&
            physicalAxis?.let { it != axis } == true
        ) {
            returnHold = axis
        } else {
            release()
        }
    }

    fun releaseAfterClose() {
        if (openingAxis == null) release()
    }

    fun release() {
        listener.disable()
        previousRequest?.let { activity.requestedOrientation = it }
        previousRequest = null
        openingAxis = null
        manualBaseline = null
        physicalAxis = null
        returnHold = null
    }

    private fun requestAxis(axis: Int) {
        activity.requestedOrientation = if (axis == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
