package com.music.zenith.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The ⋯ menu's jelly, for a [PlayerPopup]: the card slides and stretches toward
 * a finger dragged across it on a loose spring, and wobbles back when it lifts.
 *
 * The same figures as the menu's own (IosMenuTouch in the app module, which
 * shared code can't reach): a dead zone before anything moves, a capped shift,
 * a capped stretch, the follow spring while the finger is down and the springier
 * one on release. Nothing here consumes a touch, so rows and sliders still work.
 */
internal class PopupJelly(private val scope: CoroutineScope) {
    /** True under "Reduce animation": the card then holds still. */
    var reduceMotion: Boolean = false

    val shiftX = Animatable(0f)
    val shiftY = Animatable(0f)
    val stretchX = Animatable(1f)
    val stretchY = Animatable(1f)

    private var down = Offset.Zero

    fun begin(position: Offset) {
        down = position
    }

    fun move(position: Offset, width: Float, height: Float, dead: Float, maxShift: Float) {
        if (reduceMotion) return
        fun soft(d: Float): Float = if (abs(d) <= dead) 0f else (abs(d) - dead) * sign(d)
        val dx = soft(position.x - down.x)
        val dy = soft(position.y - down.y)
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        val tx = (dx * 0.07f).coerceIn(-maxShift, maxShift)
        val ty = (dy * 0.07f).coerceIn(-maxShift, maxShift)
        val ax = (abs(dx) / w * 0.22f).coerceAtMost(0.06f)
        val ay = (abs(dy) / h * 0.22f).coerceAtMost(0.06f)
        val sx = 1f + ax - ay * 0.5f
        val sy = 1f + ay - ax * 0.5f
        val follow = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)
        scope.launch { shiftX.animateTo(tx, follow) }
        scope.launch { shiftY.animateTo(ty, follow) }
        scope.launch { stretchX.animateTo(sx, follow) }
        scope.launch { stretchY.animateTo(sy, follow) }
    }

    fun finish() {
        if (reduceMotion) return
        val wobble = spring<Float>(dampingRatio = 0.34f, stiffness = 260f)
        scope.launch { shiftX.animateTo(0f, wobble) }
        scope.launch { shiftY.animateTo(0f, wobble) }
        scope.launch { stretchX.animateTo(1f, wobble) }
        scope.launch { stretchY.animateTo(1f, wobble) }
    }
}

/**
 * Applies [jelly] on top of whatever the layer already does. The stretch is
 * kept centred on the card, whatever origin the morph scales around.
 */
internal fun GraphicsLayerScope.applyPopupJelly(jelly: PopupJelly) {
    if (jelly.reduceMotion) return
    val jx = jelly.stretchX.value
    val jy = jelly.stretchY.value
    scaleX *= jx
    scaleY *= jy
    translationX += jelly.shiftX.value + (0.5f - transformOrigin.pivotFractionX) * size.width * (1f - jx)
    translationY += jelly.shiftY.value + (0.5f - transformOrigin.pivotFractionY) * size.height * (1f - jy)
}