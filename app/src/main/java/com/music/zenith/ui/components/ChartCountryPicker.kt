package com.music.zenith.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.zenith.ui.components.backdrop.Backdrop
import com.music.zenith.ui.icons.SfIcons
import com.music.zenith.ui.screens.ChartCountryOption
import com.music.zenith.ui.screens.chartCountryOptions
import dev.chrisbanes.haze.HazeState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.zenith.data.settings.AppSettings
import com.music.zenith.ui.components.backdrop.backdrops.SnapshotBackdrop

/** The popup is capped so a long country list scrolls inside it instead of filling the screen. */
private val COUNTRY_LIST_MAX_HEIGHT = 380.dp

private class CountryButtonBounds {
    var rect: Rect = Rect.Zero
}

/**
 * The Charts country opener, Apple Music style: a glass capsule with the country
 * name and the up/down chevrons that mark a menu. It swells under a finger and
 * springs back ([glassJelly]), and reports its window bounds on tap so the popup
 * can grow out of it.
 */
@Composable
fun ChartCountryButton(
    label: String,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    // Lives inside the page, which LocalAppBackdrop records: sampling it here
    // would make the button draw itself. Fill plus rim only, like the Play pills.
    val content = MaterialTheme.colorScheme.onSurface
    val bounds = remember { CountryButtonBounds() }
    Row(
        modifier = modifier
            // Before the jelly layer, so the reported bounds are the resting ones.
            .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
            .glassJelly(GLASS_JELLY_PRESS_WIDE)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
            .liquidGlassRim(shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { onClick(bounds.rect) },
            )
            .height(42.dp)
            .padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Spacer(Modifier.width(10.dp))
        UpDownChevrons(color = content.copy(alpha = 0.72f))
    }
}

/** The stacked up/down chevrons of an iOS menu opener, drawn rather than loaded. */
@Composable
private fun UpDownChevrons(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 10.dp, height = 16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val up = Path().apply {
            moveTo(0f, h * 0.34f)
            lineTo(w / 2f, h * 0.10f)
            lineTo(w, h * 0.34f)
        }
        val down = Path().apply {
            moveTo(0f, h * 0.66f)
            lineTo(w / 2f, h * 0.90f)
            lineTo(w, h * 0.66f)
        }
        drawPath(up, color, style = stroke)
        drawPath(down, color, style = stroke)
    }
}

/** The dropdown's width once open; the capsule widens from its own width to this. */
private val COUNTRY_DROPDOWN_WIDTH = 264.dp

/**
 * The Charts country picker as ONE box: the capsule button itself widens and
 * drops down into the country list, and folds back into the capsule on close.
 * Nothing else opens; the list is revealed inside the same surface. The host
 * owns [expanded] and draws this over the page, so growing never pushes the
 * content below it.
 */
@Composable
fun ChartCountryDropdown(
    selected: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
) {
    val options = remember { chartCountryOptions() }
    val label = options.firstOrNull { it.code == selected }?.name ?: selected
    val colors = iosMenuColors()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val still = backdrop as? SnapshotBackdrop
    val frost = backdrop != null && !reduceDynamicBlur && isGlassSupported() &&
        (still == null || still.hasStill)
    val content = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "chartCountryDropdown",
    )
    val p = progress.coerceIn(0f, 1f)
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    val touch = rememberJellyMenuTouch()

    // The capsule's natural width, measured once per label; the box grows from it.
    var collapsedWidth by remember(label) { mutableStateOf(0.dp) }
    // Radius 26dp is clamped to a full capsule while the box is one button tall.
    val shape = remember { RoundedCornerShape(26.dp) }
    val slab = if (frost) colors.fill.copy(alpha = 0.55f) else colors.fill
    val fill = lerpColor(content.copy(alpha = 0.08f), slab, p)
    val widthModifier = if (collapsedWidth > 0.dp) {
        Modifier.width(lerpDp(collapsedWidth, maxOf(collapsedWidth, COUNTRY_DROPDOWN_WIDTH), p))
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .iosMenuJelly(touch, 0f, 0f)
            .then(widthModifier)
            .shadow(lerpDp(0.dp, 20.dp, p), shape, clip = false)
            .clip(shape)
            .then(
                if (frost && p > 0f && backdrop != null) {
                    Modifier.menuBackdropFrost(backdrop, shape, fill)
                } else {
                    Modifier.background(fill, shape)
                },
            )
            .liquidGlassRim(shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
            .touchGlow()
            .iosMenuTouchHost(touch, colors.pressed),
    ) {
        IosMenuScrollEnvironment(touch) {
        Row(
            modifier = Modifier
                .onSizeChanged {
                    if (collapsedWidth == 0.dp) collapsedWidth = with(density) { it.width.toDp() }
                }
                .then(if (collapsedWidth > 0.dp) Modifier.fillMaxWidth() else Modifier)
                .height(42.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { onExpandedChange(!expanded) },
                )
                .padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = content,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .padding(end = 10.dp),
            )
            UpDownChevrons(color = content.copy(alpha = 0.72f))
        }
        if (p > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(lerpDp(0.dp, COUNTRY_LIST_MAX_HEIGHT, p))
                    .clipToBounds()
                    .graphicsLayer { alpha = ((p - 0.1f) / 0.6f).coerceIn(0f, 1f) },
            ) {
                CountryDropdownList(
                    options = options,
                    selected = selected,
                    colors = colors,
                    onPick = onPick,
                    modifier = Modifier
                        .requiredHeight(COUNTRY_LIST_MAX_HEIGHT)
                        .fillMaxWidth(),
                )
            }
        }
        }
    }
}

@Composable
private fun CountryDropdownList(
    options: List<ChartCountryOption>,
    selected: String,
    colors: IosMenuColors,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Opens with the chosen country in view rather than at the top of the list.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (options.indexOfFirst { it.code == selected } - 1).coerceAtLeast(0),
    )
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.separator),
        )
        Text(
            text = "Select a country",
            color = colors.secondaryLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 6.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(options, key = { it.code }) { option ->
                IosMenuRow(
                    label = option.name,
                    icon = if (option.code == selected) SfIcons.Check else null,
                    onClick = { onPick(option.code) },
                    compact = true,
                )
                if (option.code == "ZZ") IosMenuSeparator(colors)
            }
        }
    }
}

/**
 * The country popup: a frosted panel that grows out of [anchor] with the macOS
 * morph, plays jelly under a dragging finger and folds back into the button.
 * Drawn at the screen root by the host, like every other page menu.
 */
@Composable
fun ChartCountryMenu(
    open: Boolean,
    anchor: Rect?,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop?,
    hazeState: HazeState?,
) {
    val options = remember { chartCountryOptions() }
    IosMenuPopover(
        item = if (open) Unit else null,
        anchor = anchor,
        onDismiss = onDismiss,
        backdrop = backdrop,
        hazeState = hazeState,
        placement = IosMenuPlacement.BelowStart,
        dimBackground = false,
        glassRim = true,
        frostAlpha = 0.55f,
        morphAlphaRamp = 1.8f,
    ) {
        ChartCountryMenuBody(options = options, selected = selected, onPick = onPick)
    }
}

@Composable
private fun ChartCountryMenuBody(
    options: List<ChartCountryOption>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val colors = iosMenuColors()
    // Opens with the chosen country in view rather than at the top of the list.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (options.indexOfFirst { it.code == selected } - 1).coerceAtLeast(0),
    )
    Text(
        text = "Select a country",
        color = colors.secondaryLabel,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 6.dp),
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.heightIn(max = COUNTRY_LIST_MAX_HEIGHT),
    ) {
        items(options, key = { it.code }) { option ->
            IosMenuRow(
                label = option.name,
                icon = if (option.code == selected) SfIcons.Check else null,
                onClick = { onPick(option.code) },
                compact = true,
            )
            if (option.code == "ZZ") IosMenuSeparator(colors)
        }
    }
}