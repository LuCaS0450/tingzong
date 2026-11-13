package cn.musicpilgrimage.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.musicpilgrimage.app.R

@Composable internal fun AppMotionDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    image: Boolean = false,
    animated: Boolean = true,
    content: @Composable () -> Unit,
) {
    val motion = animated && LocalPhysicalMotion.current
    val progress = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion) progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1200f, visibilityThreshold = .003f))
        else progress.snapTo(1f)
    }
    Dialog(onDismissRequest, properties) {
        val view = LocalView.current
        SideEffect {
            val animation = when {
                !motion -> R.style.AppDialogNoMotion
                image -> R.style.AppDialogImageMotion
                else -> R.style.AppDialogSheetMotion
            }
            (view.parent as? DialogWindowProvider)?.window?.setWindowAnimations(animation)
        }
        Box(Modifier.graphicsLayer {
            alpha = .55f + .45f * progress.value
            if (image) {
                scaleX = .985f + .015f * progress.value
                scaleY = scaleX
            } else translationY = 18.dp.toPx() * (1f - progress.value)
        }) { content() }
    }
}
