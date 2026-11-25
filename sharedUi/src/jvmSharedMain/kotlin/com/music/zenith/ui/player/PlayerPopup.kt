package com.music.zenith.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.zenith.ui.components.MENU_FROST_ALPHA
import com.music.zenith.ui.components.MenuFrostDark
import com.music.zenith.ui.components.liveMenuFrost
import com.music.zenith.ui.components.touchGlow
import com.music.zenith.ui.utils.containSheetGestures
import dev.chrisbanes.haze.HazeState

private val POPUP_CORNER = 30.dp
internal val POPUP_SHAPE = RoundedCornerShape(POPUP_CORNER)
internal val POPUP_MAX_WIDTH = 420.dp

/** The stand-in trigger the card grows out of: the finger's point, as a small square. */
private val POPUP_ORIGIN_SIZE = 44.dp

/** Air between the card and the button it grew out of. */
private val POPUP_ANCHOR_GAP = 14.dp

/** Where the card rests when no finger point is known. */
private val POPUP_FALLBACK_BOTTOM = 96.dp

private val POPUP_OPEN_SPRING = spring<Float>(dampingRatio = 0.84f, stiffness = 320f, visibilityThreshold = 0.001f)
private val POPUP_CLOSE_SPRING = spring<Float>(dampingRatio = 1f, stiffness = 480f, visibilityThreshold = 0.01f)

/**
 * A floating card over a dimmed player, the popup counterpart of [PlayerDrawer].
 *
 * It opens with the same window morph as the app's other popups: the card grows
 * out of the point the finger touched (the button that opened it), settles just
 * above that button, and folds back into it on the way out. Tapping outside or
 * pressing back closes it; the exit is played before [onDismiss] is called,
 * because the caller removes the popup from the tree the moment it runs.
 */
@Composable
internal fun PlayerPopup(
    hazeState: HazeState,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Air between the title and whatever the popup opens with. */
    titleGap: Dp = 14.dp,
    /** The widest the card grows; the full-width popups keep the default. */
    maxWidth: Dp = POPUP_MAX_WIDTH,
    /** Drawn above the title, for a popup that opens with a logo. */
    header: (@Composable ColumnScope.() -> Unit)? = null,
    /** Drags the card with the ⋯ menu's jelly instead of the plain lean. */
    menuJelly: Boolean = false,
    /**
     * The bounds, in window space, of the button this card is the grown form of.
     * It grows out of exactly that box and rests on its bottom edge, instead of
     * growing from the finger's point.
     */
    anchorRect: Rect? = null,
    /** Told how far open the card is, on every frame of its motion. */
    onProgress: ((Float) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val frost = LocalPlayerFrost.current
    val density = LocalDensity.current
    val jellyScope = rememberCoroutineScope()
    val jelly = remember(jellyScope) { PopupJelly(jellyScope) }
    jelly.reduceMotion = reduceAnimation

    // The touch that opened this, taken once.
    val origin = remember { PopupOrigin.take() }

    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (reduceAnimation) progress.snapTo(1f) else progress.animateTo(1f, POPUP_OPEN_SPRING)
    }
    LaunchedEffect(closing) {
        if (closing) {
            if (reduceAnimation) progress.snapTo(0f) else progress.animateTo(0f, POPUP_CLOSE_SPRING)
            onDismiss()
        }
    }
    val close: () -> Unit = { closing = true }
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value }.collect { onProgress?.invoke(it) }
    }
    PlayerBackHandler(enabled = !closing) { close() }

    var card by remember { mutableStateOf(Rect.Zero) }
    var overlayBottom by remember { mutableFloatStateOf(0f) }

    // Rests just above the button it grew out of, rather than floating mid-screen.
    val bottomGap = if (anchorRect != null && overlayBottom > 0f) {
        // On the grown button's bottom edge: the card rises from it.
        with(density) {
            (overlayBottom - anchorRect.bottom).coerceIn(16.dp.toPx(), 360.dp.toPx()).toDp()
        }
    } else if (origin != null && overlayBottom > 0f) {
        with(density) {
            (overlayBottom - (origin.y - POPUP_ORIGIN_SIZE.toPx() / 2f) + POPUP_ANCHOR_GAP.toPx())
                .coerceIn(16.dp.toPx(), 360.dp.toPx())
                .toDp()
        }
    } else {
        POPUP_FALLBACK_BOTTOM
    }

    // Where a finger is on the card: the card leans toward it on a loose spring
    // and wobbles back when it lifts. Watched in the Initial pass without
    // consuming anything, so rows, the volume slider and scrolling keep working.
    var touch by remember { mutableStateOf(Offset.Zero) }
    var touching by remember { mutableStateOf(false) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val leanX by animateFloatAsState(
        targetValue = if (!menuJelly && touching && cardSize.width > 0) {
            ((touch.x / cardSize.width - 0.5f) * 2f).coerceIn(-1f, 1f)
        } else {
            0f
        },
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 260f),
        label = "playerPopupLeanX",
    )
    val leanY by animateFloatAsState(
        targetValue = if (!menuJelly && touching && cardSize.height > 0) {
            ((touch.y / cardSize.height - 0.5f) * 2f).coerceIn(-1f, 1f)
        } else {
            0f
        },
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 260f),
        label = "playerPopupLeanY",
    )
    val leanMax = 4.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .containSheetGestures()
            .onGloballyPositioned {
                if (it.isAttached) overlayBottom = it.positionInWindow().y + it.size.height
            }
            .drawBehind { drawRect(SCRIM_COLOR, alpha = progress.value.coerceIn(0f, 1f)) }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = close,
            )
            .statusBarsPadding()
            .then(if (origin == null && anchorRect == null) Modifier.navigationBarsPadding() else Modifier)
            .padding(top = 24.dp, bottom = bottomGap),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 560.dp)
                .onGloballyPositioned { c ->
                    if (c.isAttached) card = Rect(c.positionInWindow(), c.size.toSize())
                }
                .graphicsLayer {
                    val b = card
                    if (b.width <= 0f || b.height <= 0f) {
                        // Not measured yet: stay invisible rather than draw at the wrong place.
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val half = POPUP_ORIGIN_SIZE.toPx() / 2f
                    val from = if (anchorRect != null) {
                        anchorRect
                    } else if (origin != null) {
                        Rect(origin.x - half, origin.y - half, origin.x + half, origin.y + half)
                    } else {
                        Rect(b.center.x - half, b.bottom - half * 2f, b.center.x + half, b.bottom)
                    }
                    applyPopupMorph(progress.value, from, b, POPUP_CORNER.toPx())
                    translationX += leanX * leanMax.toPx()
                    translationY += leanY * leanMax.toPx()
                    if (menuJelly) applyPopupJelly(jelly)
                }
                .clip(POPUP_SHAPE)
                .then(
                    // The same frost the drawers use, so the popup and the ⋯ menu
                    // read as one material over the player.
                    when {
                        reduceDynamicBlur -> Modifier.background(MenuFrostDark)
                        frost != null -> with(frost) {
                            Modifier.frostPanel(POPUP_SHAPE, MenuFrostDark.copy(alpha = MENU_FROST_ALPHA))
                        }
                        else -> Modifier.liveMenuFrost(hazeState, MenuFrostDark)
                    },
                )
                .border(0.5.dp, Color.White.copy(alpha = 0.10f), POPUP_SHAPE)
                .onSizeChanged { cardSize = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var jellyHeld = false
                        val jellyDead = 6.dp.toPx()
                        val jellyMaxShift = 12.dp.toPx()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val finger = event.changes.firstOrNull { it.pressed }
                            if (finger != null) {
                                touch = finger.position
                                touching = true
                            } else {
                                touching = false
                            }
                            if (menuJelly) {
                                if (finger != null) {
                                    if (!jellyHeld) {
                                        jellyHeld = true
                                        jelly.begin(finger.position)
                                    } else {
                                        jelly.move(
                                            position = finger.position,
                                            width = cardSize.width.toFloat(),
                                            height = cardSize.height.toFloat(),
                                            dead = jellyDead,
                                            maxShift = jellyMaxShift,
                                        )
                                    }
                                } else if (jellyHeld) {
                                    jellyHeld = false
                                    jelly.finish()
                                }
                            }
                        }
                    }
                }
                .touchGlow()
                // Taps on the card are the card's, not the scrim's.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .popupMorphContent { progress.value }
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                header?.invoke(this)
                // A popup that draws its own title inside [header] passes none.
                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp,
                        ),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, bottom = titleGap),
                    )
                }
                content()
            }
        }
    }
}

