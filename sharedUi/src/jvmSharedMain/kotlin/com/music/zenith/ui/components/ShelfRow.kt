package com.music.zenith.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * What a sideways shelf is wrapped in, on top of the row itself.
 *
 * Nothing on the phone, where a finger drags the row. A window's mouse can't
 * swipe sideways, so the desktop wraps each row in the arrows it pages with,
 * the drag that pulls it, and the horizontal wheel.
 */
interface ShelfRowChrome {
    @Composable
    fun Wrap(state: LazyListState, row: @Composable () -> Unit)
}

private object NoShelfRowChrome : ShelfRowChrome {
    @Composable
    override fun Wrap(state: LazyListState, row: @Composable () -> Unit) = row()
}

val LocalShelfRowChrome = staticCompositionLocalOf<ShelfRowChrome> { NoShelfRowChrome }

/** What a shelf's row is doing right now, in plain fields: read as a card enters composition. */
internal class ShelfScrollFlow {
    var scrolling = false
    var forward = false

    /** Counts the moments a scroll (drag and fling) has come to rest; cards still mid-entrance settle on it. */
    var releases by mutableIntStateOf(0)

    private var lastEntryNanos = 0L
    private var wave = 0

    /**
     * Where a card coming in now stands among the ones that came in just
     * before it: 0 for the first of a run, one more for each after it.
     */
    fun nextWaveSlot(): Int {
        val now = System.nanoTime()
        wave = if (now - lastEntryNanos > SHELF_WAVE_GAP_NANOS) 0 else wave + 1
        lastEntryNanos = now
        return wave
    }
}

private const val SHELF_WAVE_GAP_NANOS = 280_000_000L
private const val SHELF_STAGGER_SHRINK = 0.1f

/** How far a card travels in from the right edge while its entrance follows the finger. */
private val SHELF_REVEAL_DISTANCE = 120.dp
private val SHELF_STAGGER_SLIDE = 72.dp
private val SHELF_STAGGER_LIFT = 10.dp

internal val LocalShelfScrollFlow = staticCompositionLocalOf<ShelfScrollFlow?> { null }

/**
 * A shelf card's entrance: a card that comes in from the right edge while its
 * row is swiped left slides in, lifts and grows to size as it crosses the first
 * stretch of the row, so it shows at once and follows the finger (and back
 * again if the swipe reverses) rather than waiting for the scroll to end.
 *
 * Each card is a little behind the one before it simply by where it sits. When
 * the scroll comes to rest, a card still part-way in finishes on a spring.
 * Decided as the card reaches the edge rather than as it is composed, so a
 * card built ahead of the swipe still plays it, while one already on screen
 * never does — a tab coming back, a page closing or a swipe to the right shows
 * its cards as they are.
 */
@Composable
fun Modifier.shelfStagger(index: Int): Modifier {
    val flow = LocalShelfScrollFlow.current ?: return this
    val revealPx = with(LocalDensity.current) { SHELF_REVEAL_DISTANCE.toPx() }
    // 1 is the card as it is; below that its entrance is under way. A card
    // composed mid-swipe starts hidden, so it never flashes at full strength.
    val driven = remember { mutableFloatStateOf(if (flow.scrolling && flow.forward) 0f else 1f) }
    // The spring that finishes the entrance once the scroll has let go.
    val settle = remember { Animatable(1f) }
    // 0: not placed yet. 1: placed beyond the right edge, waiting to come in.
    // 2: settled, nothing left to decide. 3: coming in, following the scroll.
    // Plain, so a frame of the swipe never recomposes the card.
    val phase = remember { IntArray(1) }
    LaunchedEffect(flow) {
        snapshotFlow { flow.releases }.collect {
            if (phase[0] != 3) return@collect
            phase[0] = 2
            val from = driven.floatValue
            if (from < 1f) {
                settle.snapTo(from)
                driven.floatValue = 1f
                settle.animateTo(1f, spring(dampingRatio = 0.74f, stiffness = 300f))
            }
        }
    }
    return this
        .onGloballyPositioned { coordinates ->
            if (phase[0] == 2) return@onGloballyPositioned
            val parent = coordinates.parentLayoutCoordinates ?: return@onGloballyPositioned
            val edge = parent.size.width.toFloat()
            val x = coordinates.positionInParent().x
            fun follow(): Float {
                val t = ((edge - x) / revealPx).coerceIn(0f, 1f)
                return t * t * (3f - 2f * t)
            }
            fun enter() {
                val d = follow()
                if (d >= 1f) {
                    phase[0] = 2
                    driven.floatValue = 1f
                } else {
                    phase[0] = 3
                    driven.floatValue = d
                }
            }
            when (phase[0]) {
                0 -> when {
                    // Hidden before it comes in, so it never shows at full
                    // strength for a frame as it reaches the edge.
                    x >= edge -> {
                        phase[0] = 1
                        driven.floatValue = 0f
                    }
                    // A lazy row only places a card once it is already inside
                    // the viewport, so the first placement of a card swiped in
                    // from the right is just under the edge, never beyond it.
                    flow.scrolling && flow.forward && x > edge * 0.5f -> enter()
                    else -> {
                        phase[0] = 2
                        driven.floatValue = 1f
                    }
                }
                1 -> if (x < edge - 1f) {
                    if (flow.scrolling && flow.forward) {
                        enter()
                    } else {
                        phase[0] = 2
                        driven.floatValue = 1f
                    }
                }
                3 -> if (flow.scrolling) {
                    val d = follow()
                    if (d >= 1f) {
                        phase[0] = 2
                        driven.floatValue = 1f
                    } else {
                        driven.floatValue = d
                    }
                }
            }
        }
        .graphicsLayer {
            val p = minOf(driven.floatValue, settle.value)
            if (p >= 1f) return@graphicsLayer
            val left = 1f - p
            alpha = (p * 2.5f).coerceIn(0f, 1f)
            translationX = left * SHELF_STAGGER_SLIDE.toPx()
            translationY = left * SHELF_STAGGER_LIFT.toPx()
            val scale = 1f - SHELF_STAGGER_SHRINK * left
            scaleX = scale
            scaleY = scale
        }
}

/** A sideways-scrolling shelf, in whatever the platform wraps one in — see [ShelfRowChrome].
 *
 * Snaps to each cell rather than stopping mid-card: a fling always settles with
 * a column aligned to the leading edge instead of leaving items cut in half.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfRow(
    contentPadding: PaddingValues,
    horizontalArrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val flow = remember(state) { ShelfScrollFlow() }
    LaunchedEffect(state) {
        fun position() = state.firstVisibleItemIndex.toLong() * 1_000_000L + state.firstVisibleItemScrollOffset
        var last = position()
        snapshotFlow { state.isScrollInProgress to position() }.collect { (scrolling, pos) ->
            if (pos != last) {
                flow.forward = pos > last
                last = pos
            }
            if (flow.scrolling && !scrolling) flow.releases++
            flow.scrolling = scrolling
            if (!scrolling) flow.forward = false
        }
    }
    LocalShelfRowChrome.current.Wrap(state) {
        CompositionLocalProvider(LocalShelfScrollFlow provides flow) {
            LazyRow(
                modifier = modifier,
                state = state,
                contentPadding = contentPadding,
                horizontalArrangement = horizontalArrangement,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
                content = content,
            )
        }
    }
}
