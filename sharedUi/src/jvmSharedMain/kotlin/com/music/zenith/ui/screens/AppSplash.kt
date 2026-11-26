package com.music.zenith.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/** How long the splash stays fully up before it fades out. */
private const val SPLASH_HOLD_MS = 900L

/** The wordmark's font size as a share of the screen width; Apple Music's sits at about 39% wide in all. */
private const val SPLASH_FONT_WIDTH_SHARE = 0.1f

/** The mark's height against the word's font size, the ratio [Wordmark] is tuned for. */
private const val SPLASH_LOGO_TO_FONT = 0.83f

private const val SPLASH_GAP_TO_FONT = 0.14f

/** How far the mark sits below the screen's centre; centred content moves half of the top padding. */
private val SPLASH_NUDGE_DOWN = 4.dp

/** The logo's height against the word's font size, as for the plain mark. */
private const val SPLASH_MARK_TO_FONT = 0.83f

/**
 * The word's cap height as a share of its font size. The mark is centred on the capitals and
 * aligned to the text baseline, so it follows the real glyphs rather than the font's line box,
 * whose ascent and descent differ between device fonts.
 */
private const val SPLASH_CAP_TO_FONT = 0.72f

/** Small optical drop for the mark, as a share of the word's font size. */
private const val SPLASH_MARK_NUDGE_TO_FONT = 0.04f

/**
 * A full-screen splash in Apple Music's manner: a red gradient from a lighter
 * top to a deeper bottom, with the mark and its word centred in white. Drawn
 * above everything else on a cold start, then faded out; remembered across
 * recreation so a rotation does not bring it back.
 */
@Composable
fun AppSplash(
    modifier: Modifier = Modifier,
    mark: (@Composable (Dp) -> Unit)? = null,
) {
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (visible) {
            delay(SPLASH_HOLD_MS)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(tween(350)),
        modifier = modifier.zIndex(1000f),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFFF5D73), Color(0xFFF72A45)),
                    ),
                )
                // Nothing underneath should react while the splash is up.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = SPLASH_NUDGE_DOWN * 2),
            contentAlignment = Alignment.Center,
        ) {
            val font = maxWidth * SPLASH_FONT_WIDTH_SHARE
            val capPx = with(LocalDensity.current) { (font * SPLASH_CAP_TO_FONT).toPx() }
            val nudgePx = with(LocalDensity.current) { (font * SPLASH_MARK_NUDGE_TO_FONT).toPx() }
            if (mark != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(font * SPLASH_GAP_TO_FONT),
                ) {
                    Box(Modifier.alignBy { ((it.measuredHeight + capPx) / 2 - nudgePx).toInt() }) {
                        mark(font * SPLASH_MARK_TO_FONT)
                    }
                    Text(
                        text = "Music",
                        color = Color.White,
                        fontSize = with(LocalDensity.current) { font.toSp() },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            } else {
                Wordmark(
                    logoHeight = font * SPLASH_LOGO_TO_FONT,
                    fontSize = with(LocalDensity.current) { font.toSp() },
                    color = Color.White,
                    gap = font * SPLASH_GAP_TO_FONT,
                )
            }
        }
    }
}