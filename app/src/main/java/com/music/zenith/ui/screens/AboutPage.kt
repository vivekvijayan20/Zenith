package com.music.zenith.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.zenith.BuildConfig
import com.music.zenith.R
import com.music.zenith.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.time.Instant
import java.util.Locale

private const val FALLBACK_REPO = "rezapranata089-bit/Zenith"
private const val MAX_COMMITS = 30

private val TileShape = RoundedCornerShape(16.dp)
private val GridGap = 10.dp

private data class ChangeCommit(
    val sha: String,
    val subject: String,
    val author: String,
    val whenMillis: Long,
    val url: String,
)

private data class ChangeLog(val commits: List<ChangeCommit>, val releasedMillis: Long)

private sealed interface ChangesState {
    data object Loading : ChangesState
    data class Loaded(val log: ChangeLog) : ChangesState
    data object Failed : ChangesState
}

private val changesCache = HashMap<String, ChangeLog>()
private val aboutJson = Json { ignoreUnknownKeys = true }
private val conventionalSubject = Regex("^(\\w+)(?:\\([^)]*\\))?!?:\\s*(.+)$")
private val conventionalTypes = setOf(
    "feat", "fix", "perf", "refactor", "docs", "style", "test", "build", "ci", "chore", "revert",
)

/**
 * The About page, laid out as a grid: hero, a 2x2 block of facts about this
 * build, three link tiles, the commits of this version as a timeline, and a
 * one-line credit.
 */
@Composable
internal fun AboutContent(version: String) {
    val context = LocalContext.current
    val repo = remember { BuildConfig.UPDATE_REPO.ifBlank { FALLBACK_REPO } }
    val tag = remember(version) { "v" + version.substringBefore('-') }
    val repoUrl = "https://github.com/$repo"

    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<ChangesState>(ChangesState.Loading, repo, tag, attempt) {
        value = ChangesState.Loading
        value = loadChanges(repo, tag)?.let { ChangesState.Loaded(it) } ?: ChangesState.Failed
    }

    val releasedText = when (val s = state) {
        is ChangesState.Loading -> "\u2026"
        is ChangesState.Failed -> "\u2014"
        is ChangesState.Loaded -> if (s.log.releasedMillis > 0L) {
            DateUtils.formatDateTime(
                context,
                s.log.releasedMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
            )
        } else {
            "\u2014"
        }
    }
    val changesText = when (val s = state) {
        is ChangesState.Loading -> "\u2026"
        is ChangesState.Failed -> "\u2014"
        is ChangesState.Loaded -> s.log.commits.size.toString()
    }

    AboutHero()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(GridGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
            InfoTile(Icons.Rounded.LocalOffer, "Version", version, Modifier.weight(1f))
            InfoTile(
                Icons.Rounded.Build,
                "Build",
                BuildConfig.BUILD_TYPE.replaceFirstChar { it.uppercase() },
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
            InfoTile(Icons.Rounded.CalendarMonth, "Released", releasedText, Modifier.weight(1f))
            InfoTile(Icons.Rounded.History, "Changes", changesText, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
            ActionTile(Icons.Rounded.Code, "Source", Modifier.weight(1f)) {
                context.openAboutLink(repoUrl)
            }
            ActionTile(Icons.Rounded.NewReleases, "Releases", Modifier.weight(1f)) {
                context.openAboutLink("$repoUrl/releases")
            }
            ActionTile(Icons.Rounded.BugReport, "Issues", Modifier.weight(1f)) {
                context.openAboutLink("$repoUrl/issues")
            }
        }
    }

    SectionLabel("What's new in $tag")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        when (val s = state) {
            is ChangesState.Loading -> ChangesLoadingRow()
            is ChangesState.Failed -> SettingsRow(
                icon = Icons.Rounded.Refresh,
                title = "Couldn't load changes",
                subtitle = "Check your connection, then tap to retry.",
                onClick = { attempt++ },
            )
            is ChangesState.Loaded -> {
                if (s.log.commits.isEmpty()) {
                    SettingsRow(title = "No commits found for this version")
                } else {
                    val last = s.log.commits.lastIndex
                    s.log.commits.forEachIndexed { index, commit ->
                        TimelineCommitRow(
                            commit = commit,
                            isFirst = index == 0,
                            isLast = index == last,
                            onClick = { context.openAboutLink(commit.url) },
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        SettingsRow(
            icon = Icons.Rounded.Description,
            title = "Full release notes",
            onClick = { context.openAboutLink("$repoUrl/releases/tag/$tag") },
        )
    }

    Text(
        text = "Forked from BitChord",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textDecoration = TextDecoration.Underline,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { context.openAboutLink("https://github.com/kushagrasinghx/BitChord") }
            .padding(top = 22.dp, bottom = 28.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = GROUP_INSET + 4.dp,
            end = GROUP_INSET,
            top = 26.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun AboutHero() {
    val context = LocalContext.current
    val icon = remember(context) { context.appIconBitmap(432) }
    val accent = MaterialTheme.colorScheme.primary
    val iconShape = RoundedCornerShape(21.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Digambar melebihi batas kolom (ke atas sampai bawah bar, ke bawah
                // sampai gradien habis) supaya tidak ada tepi keras di glow.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width / 2f, 80.dp.toPx()),
                        radius = 260.dp.toPx(),
                    ),
                    topLeft = Offset(0f, -400.dp.toPx()),
                    size = Size(size.width, size.height + 700.dp.toPx()),
                )
            }
            .padding(top = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .shadow(elevation = 16.dp, shape = iconShape, clip = true),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, iconShape),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = context.getString(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "A YouTube Music player with an Apple Music\u2013style interface",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun InfoTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChangesLoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(ICON_SIZE - 4.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(ICON_GAP + 2.dp))
        Text(
            text = "Loading changes\u2026",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineCommitRow(
    commit: ChangeCommit,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val (type, title) = splitSubject(commit.subject)
    val dotColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.outline
    val meta = listOfNotNull(
        commit.author.ifBlank { null },
        if (commit.whenMillis > 0L) {
            DateUtils.getRelativeTimeSpanString(
                commit.whenMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        } else {
            null
        },
    ).joinToString(" \u00b7 ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight()
                .drawBehind {
                    val cx = size.width / 2f
                    val dotY = 22.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(cx, if (isFirst) dotY else 0f),
                        end = Offset(cx, if (isLast) dotY else size.height),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawCircle(color = dotColor, radius = 4.5.dp.toPx(), center = Offset(cx, dotY))
                },
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (type != null) TypeChip(type)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = commit.sha.take(7),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun TypeChip(type: String) {
    val color = typeColor(type)
    Text(
        text = type,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private fun typeColor(type: String): Color = when (type) {
    "feat" -> Color(0xFF30B455)
    "fix" -> Color(0xFFE5484D)
    "perf" -> Color(0xFF0A84FF)
    "refactor" -> Color(0xFF9B5DE5)
    "docs" -> Color(0xFF16A6B6)
    else -> Color(0xFF8E8E93)
}

private fun splitSubject(subject: String): Pair<String?, String> {
    val match = conventionalSubject.find(subject) ?: return null to subject
    val type = match.groupValues[1].lowercase()
    return if (type in conventionalTypes) type to match.groupValues[2] else null to subject
}

private suspend fun loadChanges(repo: String, tag: String): ChangeLog? {
    val key = "$repo@$tag"
    changesCache[key]?.let { return it }
    val result = withContext(Dispatchers.IO) { runCatching { fetchChanges(repo, tag) }.getOrNull() }
    if (result != null) changesCache[key] = result
    return result
}

private fun getJson(url: String): JsonElement? {
    val request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .build()
    val body = Http.client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) null else response.body?.string()
    } ?: return null
    return aboutJson.parseToJsonElement(body)
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun parseMillis(iso: String?): Long =
    iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

/**
 * Commits between the previous release and this one. A first release lists
 * its own history; a build whose tag is not published yet (a beta) lists the
 * latest commits instead.
 */
private fun fetchChanges(repo: String, tag: String): ChangeLog? {
    val api = "https://api.github.com/repos/$repo"
    val releases = (getJson("$api/releases?per_page=30") as? JsonArray)
        .orEmpty()
        .mapNotNull { it as? JsonObject }
    val tags = releases.map { it.str("tag_name") }
    val at = tags.indexOf(tag)
    val previous = if (at >= 0) tags.getOrNull(at + 1) else null
    val released = if (at >= 0) parseMillis(releases[at].str("published_at")) else 0L

    val element = when {
        previous != null -> getJson("$api/compare/$previous...$tag")
        at >= 0 -> getJson("$api/commits?sha=$tag&per_page=$MAX_COMMITS")
        else -> getJson("$api/commits?per_page=$MAX_COMMITS")
    } ?: return null

    // compare lists oldest first, commits newest first: show newest first.
    val items: List<JsonElement> = when (element) {
        is JsonArray -> element
        is JsonObject -> (element["commits"] as? JsonArray).orEmpty().reversed()
        else -> return null
    }
    val commits = items.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val sha = obj.str("sha") ?: return@mapNotNull null
        val commit = obj["commit"] as? JsonObject ?: return@mapNotNull null
        val subject = commit.str("message").orEmpty().lineSequence().firstOrNull().orEmpty().trim()
        if (subject.isEmpty() || subject.startsWith("Merge ")) return@mapNotNull null
        val authorObj = commit["author"] as? JsonObject
        ChangeCommit(
            sha = sha,
            subject = subject,
            author = authorObj?.str("name").orEmpty(),
            whenMillis = parseMillis(authorObj?.str("date")),
            url = obj.str("html_url") ?: "https://github.com/$repo/commit/$sha",
        )
    }.take(MAX_COMMITS)
    return ChangeLog(commits, released)
}

private fun Context.appIconBitmap(sizePx: Int): ImageBitmap? = runCatching {
    val drawable = packageManager.getApplicationIcon(packageName)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()

private fun Context.openAboutLink(url: String) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
    }
}