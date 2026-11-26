package com.music.zenith.ui.screens

import com.music.zenith.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.zenith.data.model.CARD_ART_PX
import com.music.zenith.data.model.artworkAt
import com.music.zenith.ui.components.SearchPlayingBars
import com.music.zenith.ui.components.longPressMenuClickable
import com.music.zenith.ui.components.thumbnailBorder
import com.music.zenith.ui.components.coverFlightSource
import com.music.zenith.ui.components.rememberCoverFlightTap
import com.music.zenith.ui.player.CanvasFrameGrab
import com.music.zenith.data.model.HomeShelf
import com.music.zenith.data.model.ShelfItem
import com.music.zenith.data.model.Song
import com.music.zenith.data.model.UiState
import com.music.zenith.ui.components.MessageState
import com.music.zenith.ui.components.PAGE_GUTTER
import com.music.zenith.ui.components.feedSkeleton
import com.music.zenith.ui.components.instantPressClickable
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.BoxWithConstraints
import com.music.zenith.ui.components.ShelfRow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import com.music.zenith.data.canvas.CanvasArtwork
import com.music.zenith.ui.AppUi
import com.music.zenith.ui.player.CanvasArtworkPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** The countries YouTube Music publishes charts for; "ZZ" is Global. */
val CHART_COUNTRY_CODES: List<String> = listOf(
    "ZZ", "AR", "AU", "AT", "BE", "BO", "BR", "CA", "CL", "CO", "CR", "CZ", "DK", "DO", "EC",
    "EG", "SV", "EE", "FI", "FR", "DE", "GT", "HN", "HU", "IS", "IN", "ID", "IE", "IL", "IT",
    "JP", "KE", "LU", "MX", "NL", "NZ", "NI", "NG", "NO", "PA", "PY", "PE", "PL", "PT", "RO",
    "RU", "SA", "RS", "ZA", "KR", "ES", "SE", "CH", "TZ", "TR", "UG", "UA", "AE", "GB", "US",
    "UY", "ZW",
)

internal fun chartCountryName(code: String): String =
    if (code == "ZZ") "Global" else java.util.Locale("", code).displayCountry.ifBlank { code }

/** One row of the Charts country list. */
data class ChartCountryOption(val code: String, val name: String)

/** The Charts country list for a popup: Global first, then the rest alphabetically. */
fun chartCountryOptions(): List<ChartCountryOption> {
    val rest = CHART_COUNTRY_CODES
        .filter { it != "ZZ" }
        .map { ChartCountryOption(it, chartCountryName(it)) }
        .sortedBy { it.name }
    return listOf(ChartCountryOption("ZZ", chartCountryName("ZZ"))) + rest
}

/** New releases, Charts and Moods & genres as three square cards, laid out at the foot of the New tab. */
@Composable
internal fun ExploreShortcuts(
    onNewReleases: (() -> Unit)?,
    onCharts: (() -> Unit)?,
    onMoods: (() -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(top = 8.dp, bottom = 26.dp),
    ) {
        onNewReleases?.let {
            ShortcutCard(
                icon = Icons.Outlined.NewReleases,
                title = stringResource(Res.string.shelf_new_releases),
                onClick = it,
                modifier = Modifier.weight(1f),
            )
        }
        onCharts?.let {
            ShortcutCard(
                icon = Icons.AutoMirrored.Outlined.TrendingUp,
                title = "Charts",
                onClick = it,
                modifier = Modifier.weight(1f),
            )
        }
        onMoods?.let {
            ShortcutCard(
                icon = Icons.Outlined.EmojiEmotions,
                title = "Moods & genres",
                onClick = it,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ShortcutCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .aspectRatio(1f)
            .instantPressClickable(shape = shape, onClick = onClick, forgiving = true)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f), shape)
            .padding(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun LazyListScope.shelfPageItems(
    state: UiState<List<HomeShelf>>,
    keyPrefix: String,
    onRetry: () -> Unit,
    onShelfItemClick: (ShelfItem, String) -> Unit,
    onShelfItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    when (state) {
        UiState.Loading -> feedSkeleton()
        is UiState.Error -> item(key = "$keyPrefix-error") {
            MessageState(
                state.message,
                actionLabel = stringResource(Res.string.retry),
                onAction = onRetry,
            )
        }
        is UiState.Success -> itemsIndexed(
            state.data,
            key = { index, shelf -> "$keyPrefix:$index:${shelf.title}" },
        ) { _, shelf ->
            if (shelf.layout == com.music.zenith.data.model.ShelfLayout.LIST) {
                RecentShelf(
                    shelf = shelf,
                    onItemClick = { onShelfItemClick(it, shelf.title) },
                    onItemLongPress = onShelfItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            } else {
                Shelf(
                    shelf = shelf,
                    onItemClick = { onShelfItemClick(it, shelf.title) },
                    onItemLongPress = onShelfItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            }
        }
    }
}

/**
 * Which New releases covers are playing their clip. Building a player blocks the
 * main thread and a decoder is not free, so only a few play at once; the rest
 * keep their still until a slot frees up.
 */
private object ReleaseClips {
    /** Asked rather than read, so a scroll recomposes nothing. Set by the page. */
    var scrolling: () -> Boolean = { false }
    private var playing = 0

    fun tryAcquire(): Boolean {
        if (playing >= RELEASE_CLIPS_AT_ONCE) return false
        playing++
        return true
    }

    fun release() {
        if (playing > 0) playing--
    }
}

private const val RELEASE_CLIPS_AT_ONCE = 3
private const val RELEASE_CLIP_DELAY_MS = 400L
private const val RELEASE_CLIP_RETRY_MS = 500L

/** The artist off a card's "Single • Artist" credit line, which the clip lookup needs. */
private fun releaseArtistOf(subtitle: String): String? =
    subtitle.split("•").map { it.trim() }.drop(1).firstOrNull { it.isNotBlank() }

/**
 * The release's animated cover over its still, as the album page plays it.
 * Only for an album or single: a track, playlist or artist has no sleeve that
 * moves. Looked up once per card, and played only once a slot is free and the
 * page is at rest.
 */
@Composable
private fun ReleaseCardMotion(
    item: ShelfItem,
    modifier: Modifier,
    frameGrab: CanvasFrameGrab,
    onCanvas: (CanvasArtwork) -> Unit,
) {
    val browseId = item.browseId ?: return
    val isRelease = item.videoId == null &&
        (browseId.startsWith("MPRE") || browseId.startsWith("OLAK") || browseId.startsWith("VLOLAK"))
    val artist = remember(item.subtitle) { releaseArtistOf(item.subtitle) }
    if (!isRelease || artist == null || !LocalArtworkImages.current) return
    val clip by produceState<CanvasArtwork?>(null, browseId) {
        value = AppUi.host.releaseMotion(item.title, artist)
    }
    val canvas = clip ?: return
    var mounted by remember(canvas) { mutableStateOf(false) }
    val held = mounted
    DisposableEffect(held) {
        onDispose { if (held) ReleaseClips.release() }
    }
    LaunchedEffect(canvas) {
        delay(RELEASE_CLIP_DELAY_MS)
        while (true) {
            snapshotFlow { ReleaseClips.scrolling() }.first { !it }
            if (ReleaseClips.tryAcquire()) break
            delay(RELEASE_CLIP_RETRY_MS)
        }
        mounted = true
    }
    if (!mounted) return
    // Klip yang sedang diputar diserahkan ke card agar ikut terbang ke halamannya.
    LaunchedEffect(canvas) { onCanvas(canvas) }
    CanvasArtworkPlayer(
        canvas = canvas,
        isPlaying = true,
        modifier = modifier,
        pausedForTransition = ReleaseClips.scrolling(),
        frameGrab = frameGrab,
    )
}

/** One square cover with title and credit underneath, for the New releases grid. */
@Composable
private fun ReleaseGridCard(
    item: ShelfItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val coverToken = remember { Any() }
    // Klip yang sedang diputar card ini dan pembaca frame-nya, untuk cover terbang.
    var flightCanvas by remember(item.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    val frameGrab = remember { CanvasFrameGrab() }
    val openWithCover = rememberCoverFlightTap(
        // Card berisi lagu langsung diputar, jadi tidak ada halaman tujuan.
        browseId = item.browseId.takeIf { item.videoId == null },
        token = coverToken,
        action = onClick,
        grabFrame = { frameGrab.grab?.invoke() },
    )
    Column(
        modifier = modifier.longPressMenuClickable(onClick = openWithCover, onLongClick = onLongPress),
    ) {
        Box(
            modifier = Modifier
                .coverFlightSource(
                    coverToken,
                    item.thumbnailUrl.artworkAt(CARD_ART_PX),
                    12.dp,
                    canvas = flightCanvas,
                )
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .thumbnailBorder(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            ReleaseCardMotion(
                item = item,
                modifier = Modifier.fillMaxSize(),
                frameGrab = frameGrab,
                onCanvas = { flightCanvas = it },
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isCurrent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = localizeCardSubtitle(item.subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * New releases, Apple Music style: albums and singles as a two-column grid of
 * covers, tracks and artists as tidy rows, everything else as the usual shelves.
 */
private fun LazyListScope.newReleasesPageItems(
    state: UiState<List<HomeShelf>>,
    onRetry: () -> Unit,
    onShelfItemClick: (ShelfItem, String) -> Unit,
    onShelfItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    when (state) {
        UiState.Loading -> feedSkeleton()
        is UiState.Error -> item(key = "new-releases-error") {
            MessageState(
                state.message,
                actionLabel = stringResource(Res.string.retry),
                onAction = onRetry,
            )
        }
        is UiState.Success -> splitNewReleasesHero(state.data).second.forEachIndexed { shelfIndex, shelf ->
            val shelfKey = "new-releases:$shelfIndex:${shelf.title}"
            val artists = isChartArtistShelf(shelf)
            val grid = !artists &&
                shelf.layout == com.music.zenith.data.model.ShelfLayout.CARDS &&
                shelf.items.isNotEmpty() &&
                shelf.items.none { it.isVideo }
            if (artists || isChartTrackShelf(shelf)) {
                item(key = "$shelfKey:header") {
                    SectionHeader(shelf.title, shelf.subtitle)
                }
                itemsIndexed(
                    shelf.items,
                    key = { index, item -> "$shelfKey:${item.browseId ?: item.videoId}:$index" },
                    contentType = { _, _ -> "release-row" },
                ) { index, item ->
                    val current = !artists && item.videoId != null &&
                        item.videoId == currentSong?.videoId
                    ChartRankRow(
                        rank = index + 1,
                        item = item,
                        round = artists,
                        isCurrent = current,
                        isPlaying = current && isPlaying,
                        showDivider = index < shelf.items.lastIndex,
                        showRank = false,
                        onClick = { onShelfItemClick(item, shelf.title) },
                        onLongPress = onShelfItemLongPress?.let { { it(item) } },
                    )
                }
                item(key = "$shelfKey:end") { Spacer(Modifier.height(26.dp)) }
            } else if (grid) {
                item(key = "$shelfKey:header") {
                    SectionHeader(shelf.title, shelf.subtitle)
                }
                item(key = "$shelfKey:grid", contentType = "release-grid") {
                    ReleaseGridShelf(
                        shelf = shelf,
                        onItemClick = { onShelfItemClick(it, shelf.title) },
                        onItemLongPress = onShelfItemLongPress,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                    )
                }
                item(key = "$shelfKey:end") { Spacer(Modifier.height(14.dp)) }
            } else if (shelf.layout == com.music.zenith.data.model.ShelfLayout.VIDEO) {
                item(key = "$shelfKey:header") {
                    SectionHeader(shelf.title, shelf.subtitle)
                }
                item(key = "$shelfKey:video", contentType = "release-video") {
                    ReleaseVideoShelf(
                        shelf = shelf,
                        onItemClick = { onShelfItemClick(it, shelf.title) },
                        onItemLongPress = onShelfItemLongPress,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                    )
                }
                item(key = "$shelfKey:end") { Spacer(Modifier.height(26.dp)) }
            } else {
                item(key = shelfKey) {
                    if (shelf.layout == com.music.zenith.data.model.ShelfLayout.LIST) {
                        RecentShelf(
                            shelf = shelf,
                            onItemClick = { onShelfItemClick(it, shelf.title) },
                            onItemLongPress = onShelfItemLongPress,
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                        )
                    } else {
                        Shelf(
                            shelf = shelf,
                            onItemClick = { onShelfItemClick(it, shelf.title) },
                            onItemLongPress = onShelfItemLongPress,
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Splits off the two cards that lead the page. YouTube's own leading shelf (no heading,
 * so it arrives titled "For you") is used when present; otherwise the first two covers
 * of the first album grid take its place.
 */
private fun splitNewReleasesHero(
    data: List<HomeShelf>,
): Pair<List<ShelfItem>, List<HomeShelf>> {
    val cards = com.music.zenith.data.model.ShelfLayout.CARDS
    val lead = data.firstOrNull()
    if (lead != null && lead.title == "For you" && lead.layout == cards &&
        lead.items.isNotEmpty() && lead.items.none { it.isVideo } && !isChartArtistShelf(lead)
    ) {
        return lead.items.take(2) to data.drop(1)
    }
    val gridIndex = data.indexOfFirst { shelf ->
        !isChartArtistShelf(shelf) && !isChartTrackShelf(shelf) &&
            shelf.layout == cards && shelf.items.isNotEmpty() && shelf.items.none { it.isVideo }
    }
    if (gridIndex < 0) return emptyList<ShelfItem>() to data
    val source = data[gridIndex]
    val rest = source.items.drop(2)
    val shelves = data.toMutableList().apply {
        if (rest.isEmpty()) removeAt(gridIndex) else this[gridIndex] = source.copy(items = rest)
    }
    return source.items.take(2) to shelves
}

/** The two leading cards, side by side, with no heading above them. */
@Composable
private fun NewReleasesHero(
    items: List<ShelfItem>,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    val title = stringResource(Res.string.shelf_new_releases)
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = 26.dp),
    ) {
        items.forEach { card ->
            val current = card.videoId != null && card.videoId == currentSong?.videoId
            ReleaseGridCard(
                item = card,
                isCurrent = current,
                isPlaying = current && isPlaying,
                onClick = { onItemClick(card, title) },
                onLongPress = onItemLongPress?.let { { it(card) } },
                modifier = Modifier.weight(1f),
            )
        }
        if (items.size < 2) Spacer(Modifier.weight(1f))
    }
}

/** Albums & singles: two rows of covers that scroll sideways together, the next column peeking in. */
@Composable
private fun ReleaseGridShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    val columns = remember(shelf.items) { shelf.items.chunked(2) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - PAGE_GUTTER - 64.dp) / 2
        ShelfRow(
            state = rememberShelfListState("new-releases:grid:${shelf.title}"),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(
                columns,
                key = { index, _ -> "column:$index" },
                contentType = { _, _ -> "release-grid-column" },
            ) { _, pair ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.width(cardWidth),
                ) {
                    pair.forEach { card ->
                        val current = card.videoId != null && card.videoId == currentSong?.videoId
                        ReleaseGridCard(
                            item = card,
                            isCurrent = current,
                            isPlaying = current && isPlaying,
                            onClick = { onItemClick(card) },
                            onLongPress = onItemLongPress?.let { { it(card) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Music videos: wide 16:9 cards in one sideways row, the next card peeking in. */
@Composable
private fun ReleaseVideoShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = maxWidth - PAGE_GUTTER - 33.dp
        ShelfRow(
            state = rememberShelfListState("new-releases:video:${shelf.title}"),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(
                shelf.items,
                key = { index, item -> "video:${item.videoId ?: item.browseId}:$index" },
                contentType = { _, _ -> "release-video-card" },
            ) { _, item ->
                val current = item.videoId != null && item.videoId == currentSong?.videoId
                VideoShelfCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = onItemLongPress?.let { { it(item) } },
                    modifier = Modifier.width(cardWidth),
                    aspect = 16f / 9f,
                    isCurrent = current,
                    isPlaying = current && isPlaying,
                )
            }
        }
    }
}

/** The New releases page. Its title lives in the top bar, like the other pushed pages. */
@Composable
fun NewReleasesPage(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onLoad: () -> Unit,
    onRetry: () -> Unit,
    onShelfItemClick: (ShelfItem, String) -> Unit,
    onShelfItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoad() }
    DisposableEffect(listState) {
        ReleaseClips.scrolling = { listState.isScrollInProgress }
        onDispose { ReleaseClips.scrolling = { false } }
    }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "new-releases-top") { Spacer(Modifier.height(8.dp)) }
        (state as? UiState.Success)?.let { loaded ->
            val hero = splitNewReleasesHero(loaded.data).first
            if (hero.isNotEmpty()) {
                item(key = "new-releases-hero") {
                    NewReleasesHero(
                        items = hero,
                        onItemClick = onShelfItemClick,
                        onItemLongPress = onShelfItemLongPress,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                    )
                }
            }
        }
        newReleasesPageItems(
            state = state,
            onRetry = onRetry,
            onShelfItemClick = onShelfItemClick,
            onShelfItemLongPress = onShelfItemLongPress,
            currentSong = currentSong,
            isPlaying = isPlaying,
        )
    }
}

/** A chart shelf that lists artists (no track behind any row), shown as a ranked list. */
private fun isChartArtistShelf(shelf: HomeShelf): Boolean =
    shelf.items.isNotEmpty() &&
        shelf.items.all { it.videoId == null && it.browseId?.startsWith("UC") == true }

/** A chart shelf of plain tracks (no videos), shown as a ranked list. */
private fun isChartTrackShelf(shelf: HomeShelf): Boolean =
    shelf.layout != com.music.zenith.data.model.ShelfLayout.VIDEO &&
        shelf.items.size >= 3 &&
        shelf.items.all { it.videoId != null && !it.isVideo }

/**
 * One ranked chart row, Apple Music style: a big rank number (accented for the
 * top three), the art, the title and credit, and a chevron on artist rows.
 */
@Composable
private fun ChartRankRow(
    rank: Int,
    item: ShelfItem,
    round: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    showDivider: Boolean,
    showRank: Boolean = true,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val artShape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        ) {
            if (showRank) {
                Text(
                    text = rank.toString(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = if (rank <= 3) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(artShape)
                    .thumbnailBorder(artShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = localizeCardSubtitle(item.subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (round) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(
                    start = PAGE_GUTTER + if (showRank) 48.dp else 70.dp,
                    end = PAGE_GUTTER,
                ),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
        }
    }
}

/**
 * The Charts shelves: artist and track charts as ranked lists, everything else
 * (playlist charts, video charts) as the usual card shelves.
 */
private fun LazyListScope.chartsPageItems(
    state: UiState<List<HomeShelf>>,
    onRetry: () -> Unit,
    onShelfItemClick: (ShelfItem, String) -> Unit,
    onShelfItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    when (state) {
        UiState.Loading -> feedSkeleton()
        is UiState.Error -> item(key = "charts-error") {
            MessageState(
                state.message,
                actionLabel = stringResource(Res.string.retry),
                onAction = onRetry,
            )
        }
        is UiState.Success -> state.data
            .withIndex()
            .sortedBy { (_, shelf) ->
                when {
                    isChartArtistShelf(shelf) -> 2
                    isChartTrackShelf(shelf) -> 1
                    else -> 0
                }
            }
            .forEach { (shelfIndex, shelf) ->
            val shelfKey = "charts:$shelfIndex:${shelf.title}"
            val artists = isChartArtistShelf(shelf)
            val rows = if (artists) shelf.items.take(4) else shelf.items
            if (artists || isChartTrackShelf(shelf)) {
                item(key = "$shelfKey:header") {
                    SectionHeader(shelf.title, shelf.subtitle)
                }
                itemsIndexed(
                    rows,
                    key = { index, item -> "$shelfKey:${item.browseId ?: item.videoId}:$index" },
                    contentType = { _, _ -> "chart-rank-row" },
                ) { index, item ->
                    val current = !artists && item.videoId != null &&
                        item.videoId == currentSong?.videoId
                    ChartRankRow(
                        rank = index + 1,
                        item = item,
                        round = artists,
                        isCurrent = current,
                        isPlaying = current && isPlaying,
                        showDivider = index < rows.lastIndex,
                        onClick = { onShelfItemClick(item, shelf.title) },
                        onLongPress = onShelfItemLongPress?.let { { it(item) } },
                    )
                }
                item(key = "$shelfKey:end") { Spacer(Modifier.height(26.dp)) }
            } else {
                item(key = shelfKey) {
                    if (shelf.layout == com.music.zenith.data.model.ShelfLayout.LIST) {
                        RecentShelf(
                            shelf = shelf,
                            onItemClick = { onShelfItemClick(it, shelf.title) },
                            onItemLongPress = onShelfItemLongPress,
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                        )
                    } else {
                        Shelf(
                            shelf = shelf,
                            onItemClick = { onShelfItemClick(it, shelf.title) },
                            onItemLongPress = onShelfItemLongPress,
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                        )
                    }
                }
            }
        }
    }
}

/** The Charts page: a country pill that opens the country sheet, then the chart shelves. */
@Composable
fun ChartsPage(
    state: UiState<List<HomeShelf>>,
    country: String,
    listState: LazyListState,
    onLoad: () -> Unit,
    onRetry: () -> Unit,
    onCountryChange: (String) -> Unit,
    onShelfItemClick: (ShelfItem, String) -> Unit,
    onShelfItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** Draws the country opener in place of the built-in pill; used together with [onCountryMenu]. */
    countryButton: (@Composable (label: String, onClick: (Rect) -> Unit) -> Unit)? = null,
    /** Asked to open the country popup from the opener's bounds; the host draws the popup. */
    onCountryMenu: ((Rect) -> Unit)? = null,
    /**
     * Draws the country picker as one box that grows into its own dropdown. It is placed over
     * the list at the spot of the first row, so opening it never pushes the shelves around.
     */
    countryDropdown: (@Composable (
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onPick: (String) -> Unit,
    ) -> Unit)? = null,
) {
    LaunchedEffect(Unit) { onLoad() }
    var picking by remember { mutableStateOf(false) }
    var dropdownOpen by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "charts-country") {
            if (countryDropdown != null) {
                Spacer(Modifier.padding(top = 8.dp, bottom = 22.dp).size(width = 1.dp, height = 42.dp))
            } else if (countryButton != null && onCountryMenu != null) {
                Box(
                    Modifier
                        .padding(horizontal = PAGE_GUTTER)
                        .padding(top = 8.dp, bottom = 22.dp),
                ) {
                    countryButton(chartCountryName(country), onCountryMenu)
                }
            } else Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = PAGE_GUTTER)
                    .padding(top = 8.dp, bottom = 22.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .clickable { picking = true }
                    .padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    text = chartCountryName(country),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(24.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        chartsPageItems(
            state = state,
            onRetry = onRetry,
            onShelfItemClick = onShelfItemClick,
            onShelfItemLongPress = onShelfItemLongPress,
            currentSong = currentSong,
            isPlaying = isPlaying,
        )
    }
        if (countryDropdown != null) {
            val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
            LaunchedEffect(atTop) { if (!atTop) dropdownOpen = false }
            if (dropdownOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { dropdownOpen = false } },
                )
            }
            if (atTop) {
                Box(
                    Modifier.offset {
                        IntOffset(
                            PAGE_GUTTER.roundToPx(),
                            contentPadding.calculateTopPadding().roundToPx() + 8.dp.roundToPx() -
                                listState.firstVisibleItemScrollOffset,
                        )
                    },
                ) {
                    countryDropdown(dropdownOpen, { dropdownOpen = it }) { code ->
                        dropdownOpen = false
                        if (code != country) onCountryChange(code)
                    }
                }
            }
        }
    }
    if (picking && (countryButton == null || onCountryMenu == null)) {
        ChartCountrySheet(
            selected = country,
            onPick = { code ->
                picking = false
                if (code != country) onCountryChange(code)
            },
            onDismiss = { picking = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartCountrySheet(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The one on screen first, then Global, then the rest alphabetically.
    val ordered = remember(selected) {
        val rest = CHART_COUNTRY_CODES
            .filter { it != "ZZ" && it != selected }
            .sortedBy { chartCountryName(it) }
        buildList {
            if (selected != "ZZ") add(selected)
            add("ZZ")
            addAll(rest)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PAGE_GUTTER, end = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = "Select a country",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(ordered, key = { it }) { code ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(code) }
                        .padding(horizontal = PAGE_GUTTER, vertical = 14.dp),
                ) {
                    Box(Modifier.width(44.dp)) {
                        if (code == selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Text(
                        text = chartCountryName(code),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (code == "ZZ") HorizontalDivider(modifier = Modifier.padding(horizontal = PAGE_GUTTER))
            }
        }
    }
}