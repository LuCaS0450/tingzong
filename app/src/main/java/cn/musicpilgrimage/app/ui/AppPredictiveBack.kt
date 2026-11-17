package cn.musicpilgrimage.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Shared haptic feedback helper obeying Android system haptic preferences and capped
 * at one cue per meaningful gesture detent or completion event.
 */
@Stable
internal class AppHaptics(private val view: View) {
    private var lastCueKey: String? = null
    private var lastCueTimeMs: Long = 0L

    fun detent(key: String) = trigger(
        key,
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CLOCK_TICK
        else HapticFeedbackConstants.KEYBOARD_TAP,
    )

    fun dragLand(key: String) = trigger(
        key,
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END
        else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    fun confirm(key: String) = trigger(
        key,
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.LONG_PRESS,
    )

    private fun trigger(key: String, constant: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (key == lastCueKey && now - lastCueTimeMs < 240L) return
        lastCueKey = key
        lastCueTimeMs = now
        // Never set FLAG_IGNORE_VIEW_SETTING so Android user haptic settings are always honored.
        view.performHapticFeedback(constant, 0)
    }
}

@Composable
internal fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    return remember(view) { AppHaptics(view) }
}

/**
 * Predictive back bridge supporting BackEventCompat progress tracking on Android 14+
 * while keeping identical commit semantics on older devices and system back button presses.
 */
@Composable
internal fun AppPredictiveBackHandler(
    enabled: Boolean,
    onStart: () -> Unit = {},
    onProgress: (BackEventCompat) -> Unit = {},
    onCancel: () -> Unit = {},
    onBack: () -> Unit,
) {
    val dispatcherOwner = LocalOnBackPressedDispatcherOwner.current ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnBack by rememberUpdatedState(onBack)

    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (currentEnabled) currentOnStart()
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (currentEnabled) currentOnProgress(backEvent)
            }

            override fun handleOnBackCancelled() {
                currentOnCancel()
            }

            override fun handleOnBackPressed() {
                currentOnBack()
            }
        }
    }

    callback.isEnabled = enabled
    DisposableEffect(dispatcherOwner, lifecycleOwner, callback) {
        val dispatcher = dispatcherOwner.onBackPressedDispatcher
        dispatcher.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }
}
