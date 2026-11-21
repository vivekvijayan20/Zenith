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
import com.music.zenith.data.model.HomeShelf
import com.music.zenith.data.model.ShelfItem
import com.music.zenith.data.model.Song
import com.music.zenith.data.model.UiState
import com.music.zenith.ui.components.MessageState
import com.music.zenith.ui.components.PAGE_GUTTER
import com.music.zenith.ui.components.feedSkeleton
import com.music.zenith.ui.components.instantPressClickable

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
            .instantPressClickable(shape = shape, onClick = onClick)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.1f), shape)
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
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        shelfPageItems(
            state = state,
            keyPrefix = "new-releases",
            onRetry = onRetry,
            onShelfItemClick = onShelfItemClick,
            onShelfItemLongPress = onShelfItemLongPress,
            currentSong = currentSong,
            isPlaying = isPlaying,
        )
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
) {
    LaunchedEffect(Unit) { onLoad() }
    var picking by remember { mutableStateOf(false) }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "charts-country") {
            Row(
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
        shelfPageItems(
            state = state,
            keyPrefix = "charts",
            onRetry = onRetry,
            onShelfItemClick = onShelfItemClick,
            onShelfItemLongPress = onShelfItemLongPress,
            currentSong = currentSong,
            isPlaying = isPlaying,
        )
    }
    if (picking) {
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