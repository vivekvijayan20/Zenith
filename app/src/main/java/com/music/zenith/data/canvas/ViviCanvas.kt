package com.music.zenith.data.canvas

import com.music.zenith.data.DebugLog as Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Vivi Music's canvas catalogue: one JSON manifest that maps song, artist and
 * album to a looping video. Held for [TTL_MS] so a lookup is a local scan, not
 * a request per track. Same matching rules as [CommunityCanvas].
 */
object ViviCanvas {

    private const val TAG = "ViviCanvas"
    private const val MANIFEST = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json"
    private const val TTL_MS = 30L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Entry(
        val song: String,
        val artist: String,
        val album: String,
        val url: String,
    )

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var fetchedAtMs = 0L

    fun search(title: String, artist: String, album: String?): CanvasArtwork? {
        val index = manifest().ifEmpty { return null }

        val wantTitle = title.normalizeForMatch()
        val wantArtist = artist.normalizeForMatch()
        val wantAlbum = album?.normalizeForMatch()

        // Judul persis lebih dulu, baru sebagian: "Starboy" tidak boleh kalah oleh entri
        // lain yang kebetulan memuat kata itu.
        fun find(exact: Boolean) = index.firstOrNull { entry ->
            val song = entry.song.stripTags().normalizeForMatch()
            val credited = entry.artist.normalizeForMatch()
            val listed = entry.album.normalizeForMatch()
            val titleOk = song.isNotBlank() &&
                if (exact) song == wantTitle else (wantTitle.contains(song) || song.contains(wantTitle))
            val artistOk = credited.isNotBlank() &&
                (wantArtist.contains(credited) || credited.contains(wantArtist))
            val albumOk = listed.isBlank() || wantAlbum.isNullOrBlank() ||
                listed == wantAlbum ||
                entry.album.stripTags().normalizeForMatch() == album?.stripTags()?.normalizeForMatch()
            titleOk && artistOk && albumOk
        }
        val hit = find(exact = true) ?: find(exact = false) ?: return null

        Log.d(TAG, "manifest hit for '${hit.song}' by '${hit.artist}'")
        // Sudah dicocokkan di atas; judul dan artis yang sedang diputar dikembalikan supaya
        // "(feat. ...)" di manifest tidak ditolak pengecekan ketat CanvasArtwork.matches.
        return CanvasArtwork(
            url = hit.url,
            title = title,
            artist = artist,
            album = null,
            source = CanvasSource.VIVI,
        )
    }

    fun searchAlbum(album: String, artist: String): CanvasArtwork? {
        val index = manifest().ifEmpty { return null }

        val wantAlbum = album.normalizeForMatch()
        val wantArtist = artist.normalizeForMatch()
        if (wantAlbum.isBlank()) return null

        val hit = index.firstOrNull { entry ->
            val listed = entry.album.normalizeForMatch()
            val credited = entry.artist.normalizeForMatch()
            listed == wantAlbum && credited.isNotBlank() &&
                (wantArtist.contains(credited) || credited.contains(wantArtist))
        } ?: return null

        Log.d(TAG, "manifest hit for album '${hit.album}' by '${hit.artist}'")
        return CanvasArtwork(
            url = hit.url,
            title = album,
            artist = artist,
            album = album,
            source = CanvasSource.VIVI,
        )
    }

    private val NOISE = Regex(
        """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
            """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun String.stripTags(): String =
        replace(NOISE, " ")
            .substringBefore(" | ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { this }

    @Synchronized
    private fun manifest(): List<Entry> {
        val now = System.currentTimeMillis()
        if (entries.isNotEmpty() && now - fetchedAtMs < TTL_MS) return entries

        val body = canvasGet(MANIFEST, mapOf("User-Agent" to CANVAS_UA))
        if (body == null) {
            fetchedAtMs = now
            return entries
        }

        val parsed = runCatching { parse(body) }.getOrNull().orEmpty()
        if (parsed.isNotEmpty()) {
            Log.d(TAG, "manifest holds ${parsed.size} entries")
            entries = parsed
        } else {
            Log.d(TAG, "manifest had no usable entries")
        }
        fetchedAtMs = now
        return entries
    }

    /** Accepts a bare array or an object holding one, and a few common key names. */
    private fun parse(body: String): List<Entry> {
        val root = json.parseToJsonElement(body)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf("items", "canvases", "tracks", "data")
                .firstNotNullOfOrNull { root[it] as? JsonArray }
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            Entry(
                song = obj.text("song", "title", "name") ?: return@mapNotNull null,
                artist = obj.text("artist") ?: return@mapNotNull null,
                album = obj.text("album").orEmpty(),
                url = obj.text("url", "animated", "videoUrl", "video") ?: return@mapNotNull null,
            )
        }
    }

    private fun JsonObject.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
}