package com.example.xuebimc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * iOS-style press feedback: the pressed element fades, it never grows a ripple or a square
 * highlight. Shape-agnostic, so it looks right on text, icons, rows and pills alike.
 */
object PressFadeIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressFadeNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 1
}

private class PressFadeNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val fade = Animatable(1f)
    private val paint = Paint()

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            var animation: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val target = if (pressed > 0) .45f else 1f
                val velocity = fade.velocity
                animation?.cancel()
                animation = coroutineScope.launch {
                    if (target < 1f) fade.animateTo(target, tween(70)) { invalidateDraw() }
                    else fade.animateTo(1f, spring(dampingRatio = 1f, stiffness = 500f), initialVelocity = velocity) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val alpha = fade.value
        if (alpha >= 0.999f) {
            drawContent()
            return
        }
        paint.alpha = alpha
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}
