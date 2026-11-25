package com.music.zenith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import com.music.zenith.data.settings.ThemeMode
import com.music.zenith.ui.icons.SfIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.zenith.R
import com.music.zenith.auth.GoogleAccountSession
import com.music.zenith.auth.YouTubeProfile
import com.music.zenith.data.settings.AppSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** Where the profile button was last tapped, in window pixels; read once by the account card. */
internal object AccountPopupAnchor {
    @Volatile
    var center: androidx.compose.ui.geometry.Offset? = null

    /** Window y where the card's top edge should sit; null keeps the top-bar default. */
    @Volatile
    var top: Float? = null
}

/** Shared compact selector for every visible YouTube account avatar. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AccountProfileSelector(
    accounts: List<GoogleAccountSession>,
    activeAccountId: String?,
    activeProfileId: String?,
    hazeState: HazeState,
    onSelect: (GoogleAccountSession, YouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (GoogleAccountSession) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onDismiss: () -> Unit,
    /** Called the moment the exit starts, so what the card covered can come back with it. */
    onClosing: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val profileOverride by AppSettings.profileOverride.collectAsStateWithLifecycle()
    // Switching list is only useful when there is more than one profile to pick from.
    val hasOtherProfiles = accounts.sumOf { it.profiles.size } > 1
    val activeProfile = accounts.firstOrNull { it.accountId == activeAccountId }?.let { acc ->
        acc.profiles.firstOrNull { it.profileId == activeProfileId }?.let { raw ->
            if (profileOverride.owner == AppSettings.profileOwnerOf(acc.email)) {
                val photo = profileOverride.photoPath
                    .takeIf { it.isNotEmpty() && java.io.File(it).exists() }
                    ?.let { "file://$it" }
                raw.copy(
                    name = profileOverride.name.ifBlank { raw.name },
                    avatar = photo ?: raw.avatar,
                )
            } else {
                raw
            }
        }
    }
    val touch = rememberIosMenuTouch(reduceMotion)
    val shape = RoundedCornerShape(22.dp)
    val surface = MaterialTheme.colorScheme.surface
    val scrim = MaterialTheme.colorScheme.scrim
    val dark = surface.luminance() < 0.5f
    // The thin material lets too much of the page through behind the text. Dark
    // mode gets a black wash over it; light mode a white one, which keeps the
    // dark text readable over a busy cover instead of muddying it.
    val material = HazeMaterials.thin(surface)
    val style = remember(material, dark) {
        HazeStyle(
            backgroundColor = material.backgroundColor,
            tints = material.tints + HazeTint(
                if (dark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.40f),
            ),
            blurRadius = material.blurRadius,
            noiseFactor = material.noiseFactor,
            fallbackTint = material.fallbackTint,
        )
    }

    // pop: the card's scale, which may overshoot — that is the bounce.
    // fade: its alpha and the scrim's. reveal: the avatars' own, a beat behind.
    val pop = remember { Animatable(if (reduceMotion) 1f else 0.12f) }
    val fade = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    // The card's own alpha on the way out: it stays solid while folding into the
    // button and fades only in the last beat, so it never vanishes half-shrunk.
    val cardFade = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    // Where the profile button sits, so the card grows out of it and folds back into it.
    val anchor = remember { AccountPopupAnchor.center.also { AccountPopupAnchor.center = null } }
    val anchorTop = remember { AccountPopupAnchor.top.also { AccountPopupAnchor.top = null } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topPad = anchorTop?.let { with(density) { it.toDp() } }?.coerceAtMost(360.dp) ?: 56.dp
    var cardOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var cardSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            reveal.snapTo(1f)
            fade.animateTo(1f, tween(120))
        } else {
            launch { fade.animateTo(1f, tween(160, easing = FastOutSlowInEasing)) }
            launch { pop.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f)) }
            launch {
                delay(110)
                if (!closing) reveal.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f))
            }
        }
    }

    // Every way out goes through here: the exit plays first, then the caller's
    // own action runs, so nothing removes the card while it is still leaving.
    val close: (() -> Unit) -> Unit = { after ->
        if (!closing) {
            closing = true
            onClosing()
            scope.launch {
                if (reduceMotion) {
                    fade.animateTo(0f, tween(110))
                } else {
                    launch { reveal.animateTo(0f, tween(110, easing = FastOutLinearInEasing)) }
                    launch { fade.animateTo(0f, tween(220, easing = FastOutLinearInEasing)) }
                    launch { cardFade.animateTo(0f, tween(70, delayMillis = 210, easing = FastOutLinearInEasing)) }
                    // Folds all the way down to the profile button's size in one continuous motion.
                    pop.animateTo(0.12f, tween(280, easing = FastOutSlowInEasing))
                }
                after()
            }
        }
    }

    // Registered after the caller's own BackHandler, so it wins and Back
    // plays the exit instead of cutting the card.
    BackHandler(enabled = !closing) { close(onDismiss) }

    Column(
        modifier = modifier.fillMaxSize()
            .drawBehind { drawRect(scrim.copy(alpha = 0.40f * fade.value.coerceIn(0f, 1f))) }
            .clickable(onClick = { close(onDismiss) }),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier
                .padding(top = topPad, start = 20.dp, end = 12.dp)
                .widthIn(max = 236.dp)
                .fillMaxWidth()
                .onGloballyPositioned {
                    cardOrigin = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                    cardSize = androidx.compose.ui.geometry.Size(
                        it.size.width.toFloat(),
                        it.size.height.toFloat(),
                    )
                }
                .graphicsLayer {
                    val s = if (reduceMotion) 1f else pop.value.coerceAtLeast(0f)
                    scaleX = s
                    scaleY = s
                    // Held back until the card is measured, so its pivot is the tapped
                    // button from the first frame instead of jumping into place.
                    val measured = if (cardSize.width > 0f) 1f else 0f
                    alpha = (if (closing && !reduceMotion) cardFade.value else fade.value).coerceIn(0f, 1f) * measured
                    // Grows out of, and folds back into, the profile button itself.
                    val ox = if (anchor != null && cardSize.width > 0f) {
                        (anchor.x - cardOrigin.x) / cardSize.width
                    } else 0.92f
                    val oy = if (anchor != null && cardSize.height > 0f) {
                        (anchor.y - cardOrigin.y) / cardSize.height
                    } else 0f
                    transformOrigin = TransformOrigin(ox, oy)
                    if (!reduceMotion) applyIosMenuJelly(touch, s, ox, oy)
                }
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = style,
                        )
                    },
                )
                // The same hairline the mini player and the nav bar wear.
                .border(
                    0.5.dp,
                    // White on dark like the bars; a light surface has nothing
                    // for a white hairline to show against.
                    if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f),
                    shape,
                )
                .clickable(onClick = {}),
        ) {
            // Room under the last row to match the space between rows, so the
            // list doesn't stop short against the card's edge.
            IosMenuScrollEnvironment(touch) {
            LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp).iosMenuTouchHost(touch, iosMenuColors().pressed),
                contentPadding = PaddingValues(bottom = 6.dp),
            ) {
                item {
                    ActiveProfileHeader(
                        profile = activeProfile,
                        reveal = { reveal.value },
                    )
                }
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                    )
                }
                if (hasOtherProfiles) accounts.forEach { account ->
                item {
                    Text(account.email.ifBlank { account.name.ifBlank { stringResource(R.string.accounts) } },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp, 8.dp, 18.dp, 2.dp))
                }
                items(account.profiles.size) { index ->
                    val raw = account.profiles[index]
                    val selected = account.accountId == activeAccountId && raw.profileId == activeProfileId
                    // Nama dan foto buatan pengguna berlaku untuk profil yang sedang aktif.
                    val profile = if (selected && profileOverride.owner == AppSettings.profileOwnerOf(account.email)) {
                        val photo = profileOverride.photoPath
                            .takeIf { it.isNotEmpty() && java.io.File(it).exists() }
                            ?.let { "file://$it" }
                        raw.copy(
                            name = profileOverride.name.ifBlank { raw.name },
                            avatar = photo ?: raw.avatar,
                        )
                    } else {
                        raw
                    }
                    ProfileRow(
                        profile = profile,
                        selected = selected,
                        managing = false,
                        reveal = { reveal.value },
                        onClick = { onSelect(account, profile); close(onDismiss) },
                        onRemove = { close { onRemoveAccount(account) } },
                    )
                }
                }
                item { SelectorAction(SfIcons.AddAccount, stringResource(R.string.add_account), onClick = { close(onAddAccount) }) }
                item {
                    SelectorAction(
                        if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        stringResource(if (dark) R.string.light else R.string.dark),
                        onClick = { AppSettings.setThemeMode(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
                    )
                }
                item { SelectorAction(SfIcons.Tune, stringResource(R.string.equalizer), onClick = { close(onOpenEqualizer) }) }
                item { SelectorAction(SfIcons.Settings, stringResource(R.string.settings), onClick = { close(onOpenSettings) }) }
            }
            }
        }
    }
}

@Composable private fun ProfileRow(profile: YouTubeProfile, selected: Boolean, managing: Boolean,
    reveal: () -> Float, onClick: () -> Unit, onRemove: () -> Unit) {
    val description = stringResource(if (selected) R.string.selected_account else R.string.switch_account)
    // The avatar and the tick scale and fade in a beat after the card, and out
    // before it leaves. The spring's overshoot on [reveal] is what makes it bounce.
    val revealFx = Modifier.graphicsLayer {
        val r = reveal()
        val s = r.coerceAtLeast(0f)
        scaleX = s
        scaleY = s
        alpha = r.coerceIn(0f, 1f)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        .jellyMenuRow(role = Role.RadioButton, onClick = if (managing) onRemove else onClick)
        .heightIn(min = 48.dp)
        .semantics { contentDescription = description }
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (profile.avatar != null) AsyncImage(profile.avatar, null, Modifier.size(32.dp).then(revealFx).clip(MaterialTheme.shapes.extraLarge))
        else Icon(SfIcons.AccountPlaceholder, null, Modifier.size(32.dp).then(revealFx), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(profile.handle.ifBlank { stringResource(if (profile.isBrandAccount) R.string.brand_account else R.string.personal) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected && !managing) Icon(Icons.Rounded.Check, stringResource(R.string.selected_account), Modifier.then(revealFx), tint = MaterialTheme.colorScheme.primary)
        if (managing) Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun SelectorAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconSize: Dp = 20.dp,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)
        .jellyMenuRow(onClick = onClick)
        .heightIn(min = 40.dp)
        .semantics { contentDescription = label }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Apple-style: the label leads, the glyph trails.
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Icon(icon, null, Modifier.size(iconSize))
    }
}

/** The active profile, centered: big avatar, name under it, handle under that. Tap to fold the account list open. */
@Composable private fun ActiveProfileHeader(
    profile: YouTubeProfile?,
    reveal: () -> Float,
) {
    val revealFx = Modifier.graphicsLayer {
        val r = reveal()
        val s = r.coerceAtLeast(0f)
        scaleX = s
        scaleY = s
        alpha = r.coerceIn(0f, 1f)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile?.avatar != null) {
            AsyncImage(profile.avatar, null, Modifier.size(44.dp).then(revealFx).clip(CircleShape))
        } else {
            Icon(SfIcons.AccountPlaceholder, null, Modifier.size(44.dp).then(revealFx), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile?.name.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            if (profile != null) {
                Text(
                    profile.handle.ifBlank { stringResource(if (profile.isBrandAccount) R.string.brand_account else R.string.personal) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
