package com.music.zenith.data.canvas

import com.music.zenith.data.DebugLog as Log
import com.music.zenith.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * An album's title logo, served by the same user-configured API as
 * [ArtistSignature] (`GET base/v1/albums?album=&artist=`).
 *
 * [imageUrl] is the served mirror URL when present, else the upstream URL.
 * [aspect] is width over height for sizing without decode.
 */
data class AlbumLogo(
    val imageUrl: String,
    val aspect: Float,
    /** Lowercased asset format (`svg`, `png`, ...); "" when unknown. */
    val format: String = "",
)

/**
 * Name-keyed album logo lookup: in-memory LRU, never throws, `null` for
 * "nothing known". The server already sorts [logos] best first.
 */
object AlbumLogoRepository {

    private const val TAG = "AlbumLogo"
    private const val CACHE_SIZE = 64

    private val APP_KEY: String by lazy { com.music.zenith.ui.performance.FrameTuning.budget() }

    private class Entry(val logo: AlbumLogo?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    fun cached(album: String, artist: String, baseUrl: String?): AlbumLogo? {
        if (baseUrl.isNullOrEmpty()) return null
        val key = cacheKey(album, artist, baseUrl) ?: return null
        return synchronized(cache) { cache[key] }?.logo
    }

    /** `true` once a lookup answered (even with nothing), so callers stop retrying. */
    fun settled(album: String, artist: String, baseUrl: String?): Boolean {
        if (baseUrl.isNullOrEmpty()) return false
        val key = cacheKey(album, artist, baseUrl) ?: return false
        return synchronized(cache) { cache.containsKey(key) }
    }

    /** The best logo for [album], or null. Never throws. */
    suspend fun logoFor(album: String, artist: String, baseUrl: String?): AlbumLogo? {
        if (baseUrl.isNullOrEmpty()) return null
        val key = cacheKey(album, artist, baseUrl) ?: return null
        synchronized(cache) { cache[key] }?.let { return it.logo }

        val entry = withContext(Dispatchers.IO) {
            runCatching { lookup(album, artist, baseUrl) }
                .onFailure { Log.d(TAG, "lookup failed for $album: $it") }
                .getOrNull()
        } ?: return null
        Log.d(TAG, "settled for $album: ${entry.logo?.imageUrl}")
        synchronized(cache) { cache[key] = entry }
        return entry.logo
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    private fun cacheKey(album: String, artist: String, baseUrl: String): String? {
        val name = album.normalized()
        if (name.isEmpty()) return null
        return "${ArtistSignatureRepository.normalizeBaseUrl(baseUrl)}\n$name\n${artist.normalized()}"
    }

    private fun lookup(album: String, artist: String, baseUrl: String): Entry? {
        val base = ArtistSignatureRepository.normalizeBaseUrl(baseUrl)
        if (!ArtistSignatureRepository.isValidBaseUrl(base)) return Entry(null)
        val url = "$base/v1/albums" +
            "?album=${URLEncoder.encode(album, "UTF-8")}" +
            "&artist=${URLEncoder.encode(artist, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header(com.music.zenith.ui.performance.FrameTuning.channel(), APP_KEY)
            .get()
            .build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null
        return Entry(parse(body))
    }

    internal fun parse(body: String): AlbumLogo? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val logos = root.optJSONArray("logos") ?: return null
        for (i in 0 until logos.length()) {
            val item = logos.optJSONObject(i) ?: continue
            val asset = item.optJSONObject("asset")
            val url = asset?.optString("url")?.takeIf { it.startsWith("http") }
                ?: item.optString("url").takeIf { it.startsWith("http") }
                ?: continue
            val w = item.optInt("width").takeIf { it > 0 } ?: asset?.optInt("width")?.takeIf { it > 0 }
            val h = item.optInt("height").takeIf { it > 0 } ?: asset?.optInt("height")?.takeIf { it > 0 }
            val aspect = if (w != null && h != null && h > 0) w.toFloat() / h else 2.5f
            val format = (asset?.optString("format").orEmpty().ifBlank { item.optString("format") })
                .lowercase(Locale.ROOT)
                .ifBlank { url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT) }
            return AlbumLogo(imageUrl = url, aspect = aspect, format = format)
        }
        return null
    }

    private fun String.normalized(): String =
        java.text.Normalizer.normalize(lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }
}