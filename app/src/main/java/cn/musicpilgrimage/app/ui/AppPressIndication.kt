package cn.musicpilgrimage.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class AppPressIndication(val motion: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        AppPressNode(interactionSource, motion)
}

private class AppPressNode(
    private val source: InteractionSource,
    private val motion: Boolean,
) : Modifier.Node(), DrawModifierNode {
    private val interactions = mutableSetOf<Interaction>()
    private val shade = Animatable(0f)
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            shade.snapTo(0f)
            source.interactions.collectLatest { event ->
                when (event) {
                    is PressInteraction.Press, is FocusInteraction.Focus, is HoverInteraction.Enter -> interactions.add(event)
                    is PressInteraction.Release -> interactions.remove(event.press)
                    is PressInteraction.Cancel -> interactions.remove(event.press)
                    is FocusInteraction.Unfocus -> interactions.remove(event.focus)
                    is HoverInteraction.Exit -> interactions.remove(event.enter)
                }
                pressed = interactions.any { it is PressInteraction.Press }
                val target = when {
                    pressed -> .13f
                    interactions.isNotEmpty() -> .07f
                    else -> 0f
                }
                invalidateDraw()
                if (pressed || !motion) shade.snapTo(target)
                else shade.animateTo(target, tween(105, easing = appMotionEasing))
            }
        }
    }

    override fun onDetach() {
        interactions.clear()
        pressed = false
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = if (pressed) .13f else shade.value
        if (alpha > 0f) drawRect(Color.Black.copy(alpha = alpha))
    }
}
