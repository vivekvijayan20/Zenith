package com.music.zenith.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.music.zenith.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The lyrics already sitting inside a downloaded file.
 *
 * The read side of what the download path wrote — see `MediaTagger`, and the
 * three taggers under it. A track that was downloaded had its lyrics fetched
 * once, at download time, and written into the file; asking four servers for
 * them again every time it is played is a network round trip to arrive at a
 * string that is already on disk, and it is the reason a downloaded song showed
 * nothing at all with the connection off.
 *
 * Two fields are read, in this order:
 *
 *  - `ZENITH_LYRICS`, this app's own, holding the "enhanced" A2 form with the
 *    word timings intact — see [toEnhancedLrc].
 *  - the container's standard lyrics field, holding plain `[mm:ss.xx]` LRC.
 *
 * The second is what every other player reads and what older downloads have,
 * so it is the fallback rather than the exception. Preferring the first is what
 * keeps a downloaded song lighting up word by word instead of a line at a time.
 *
 * Never throws. A file that isn't one of the three containers, or is one and
 * has no lyrics in it, is a null — the caller falls back to the network, which
 * is exactly what it did before this existed.
 *
 * ### Offline packages
 *
 * One kind of download has no container to have been written into: an HLS or
 * DASH stream is saved as a directory of fMP4 segments behind a playlist, and
 * its `localUri` points at that playlist. `OfflineHls` and `OfflineDash` write
 * the lyrics beside it as a `lyrics.lrc` sidecar for exactly that reason — so
 * [sidecar] is tried first, and a package's lyrics come back the same shape as
 * a tagged file's. Without it those tracks fetched their lyrics from the
 * network on every play and showed none at all offline, which is the failure
 * this whole object exists to prevent.
 */
object EmbeddedLyrics {

    private const val TAG = "Zenith"

    /**
     * Most bytes worth pulling to find a tag.
     *
     * A cap rather than a size: this reads whatever region of the file holds
     * the metadata, and that region is small in all three containers — but its
     * length is stated *by the file*, so a corrupt or hostile one could claim
     * any number at all. `LyricsTag` caps what it writes at 64k, so anything
     * past this is not a tag this app produced.
     */
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    /**
     * The lyrics inside [uriString], or null when it has none worth showing.
     *
     * Touches the filesystem, so it runs on [Dispatchers.IO] regardless of
     * where it is called from.
     */
    suspend fun forUri(context: Context, uriString: String): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val raw = runCatching { read(context, Uri.parse(uriString)) }
                .onFailure { Log.d(TAG, "no embedded lyrics in $uriString: ${it.message}") }
                .getOrNull()
                ?: return@withContext null
            // The same last pass the network sources get, so a downloaded track
            // and a streamed one draw their backing vocals the same way.
            // TTML (Apple's word-timed format) is told apart by its XML header;
            // everything else is LRC or enhanced LRC.
            val ttml = isTtml(raw)
            val parsed = if (ttml) TtmlLyrics.parse(raw) else LrcLib.parseLrc(raw)
            parsed.takeIf { l -> l.any { it.text.isNotBlank() } }
                ?.let { if (ttml) it else it.withBackgroundVocals() }
        }

    /** The raw LRC text for the file, preferring this app's word-timed field. */
    private fun read(context: Context, uri: Uri): String? =
        sidecar(uri.path.takeIf { uri.scheme == "file" })
            ?: open(context, uri)?.use { fromBytes(it.readAtMost(MAX_TAG_BYTES)) }
            ?: localSidecar(context, uri)

    /**
     * The `lyrics.lrc` written next to the offline package playlist at [path].
     *
     * Recognised by the playlist rather than by the directory it sits in: the
     * package lives under a path this module has no business knowing, and a
     * local `.m3u8` is only ever one of these packages — nothing else in the
     * app saves a playlist to disk. What the sidecar holds is the enhanced A2
     * form where the download found one, which is why it is preferred over a
     * container search rather than used as a fallback to it.
     *
     * Takes the path rather than the `Uri` it came out of so the pairing with
     * the writers can be tested on a real directory, without a device.
     */
    internal fun sidecar(path: String?): String? {
        val playlist = path?.let(::File)?.takeIf { it.name.endsWith(".m3u8", ignoreCase = true) }
            ?: return null
        val file = File(playlist.parentFile ?: return null, "lyrics.lrc")
        // Bounded for the same reason [MAX_TAG_BYTES] is: this is read into a
        // string, and the size on disk is the only thing that says how big one.
        if (!file.isFile || file.length() !in 1..MAX_TAG_BYTES.toLong()) return null
        return runCatching { file.readText() }
            .onFailure { Log.d(TAG, "could not read $file: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * The raw LRC text in [head], whichever of the three containers it is.
     *
     * Split from [read] so the parsing can be tested against bytes a tagger
     * just produced, without a device or a `Context` in the way — the round
     * trip is the only thing that proves a reader and a writer agree.
     */
    internal fun fromBytes(head: ByteArray): String? {
        val found = when {
            head.startsWith(ID3_MAGIC) -> id3(head)
            head.startsWith(OGG_MAGIC) -> ogg(head)
            head.startsWith(FLAC_MAGIC) -> flac(head)
            head.startsWith(MATROSKA_MAGIC) -> matroska(head)
            head.isMp4() -> mp4(head)
            else -> null
        }
        return found?.takeIf { it.isNotBlank() }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }

    // ---- MP4 / M4A ----------------------------------------------------------

    /**
     * The `©lyr` atom's text, or this app's freeform one where it is present.
     *
     * Both live under `moov/udta/meta/ilst`, and the search is scoped to `moov`
     * rather than run over the file: a four-byte pattern turns up in audio data
     * often enough that scanning the whole thing would eventually read a frame
     * as a tag. Inside `moov` the same pattern is a tag or it is nothing.
     */
    private fun mp4(bytes: ByteArray): String? {
        val moov = topLevelBox(bytes, "moov") ?: return null
        // Exclusive, and deliberately so: the lyrics are the last item written
        // into `ilst`, so their value ends exactly on `moov`'s own end — an
        // inclusive bound here rejects the one atom this is looking for.
        val end = moov.last + 1
        return ilstText(bytes, moov.first, end, freeform = true)
            ?: ilstText(bytes, moov.first, end, freeform = false)
    }

    /**
     * The bounds of a top-level box, without walking into it.
     *
     * `moov` is not required to come before the audio — a file written without
     * the faststart pass puts it after `mdat` — so this steps box to box rather
     * than assuming a position.
     */
    private fun topLevelBox(bytes: ByteArray, type: String): IntRange? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val declared = readU32(bytes, pos)
            var headerLen = 8
            var size = declared
            if (declared == 1L) {
                if (pos + 16 > bytes.size) return null
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (declared == 0L) {
                size = (bytes.size - pos).toLong()
            }
            if (size < headerLen || size > Int.MAX_VALUE) return null
            val end = (pos + size).toInt().coerceAtMost(bytes.size)
            if (String(bytes, pos + 4, 4, Charsets.ISO_8859_1) == type) return pos until end
            pos += size.toInt()
        }
        return null
    }

    /**
     * The text of the lyrics item inside [within].
     *
     * [freeform] picks which of the two: this app's `----` item, whose name is
     * carried in a `name` box beside the value, or the standard `©lyr`, whose
     * four-byte type *is* the name. They are stored differently enough that one
     * search cannot find both.
     */
    private fun ilstText(bytes: ByteArray, from: Int, endExclusive: Int, freeform: Boolean): String? {
        val marker = if (freeform) WORD_LYRICS_FIELD.toByteArray(Charsets.UTF_8) else LYR_ATOM
        var at = from
        while (true) {
            val found = bytes.indexOf(marker, at, endExclusive) ?: return null
            // The value is the first `data` box after the name, in both layouts:
            // a freeform item is mean/name/data, a standard one is type/data.
            val data = bytes.indexOf(DATA_ATOM, found, endExclusive) ?: return null
            dataText(bytes, data, endExclusive)?.let { return it }
            at = found + marker.size
        }
    }

    /**
     * An iTunes `data` box's payload as text.
     *
     * The box is version/flags(4) + locale(4) + the value, and the length in
     * front of it is what says where the value stops — a lyric sheet has
     * newlines in it and nothing else terminates it.
     */
    private fun dataText(bytes: ByteArray, dataAt: Int, endExclusive: Int): String? {
        val start = dataAt - 4
        if (start < 0 || dataAt + 12 > endExclusive) return null
        val size = readU32(bytes, start).toInt()
        if (size <= 16 || start + size > endExclusive) return null
        // Type indicator 1 is UTF-8 text; a cover's 13/14 is the other thing a
        // `data` box holds, and decoding a JPEG as a string is not a lyric.
        if (readU32(bytes, dataAt + 4).toInt() != 1) return null
        return String(bytes, dataAt + 12, start + size - (dataAt + 12), Charsets.UTF_8)
    }

    // ---- MP3 (ID3v2) --------------------------------------------------------

    /**
     * Lirik dari tag ID3v2: frame `USLT` (dipakai hampir semua tagger, isinya
     * bisa LRC/eLRC/TTML) atau `TXXX` bernama lyrics.
     */
    private fun id3(bytes: ByteArray): String? {
        if (bytes.size < 10) return null
        val version = bytes[3].toInt() and 0xFF
        if (version !in 2..4) return null
        val flags = bytes[5].toInt() and 0xFF
        val end = minOf(10 + synchsafe(bytes, 6), bytes.size)
        if (end <= 10) return null
        var body = bytes.copyOfRange(10, end)
        if (flags and 0x80 != 0) body = deunsync(body)
        var pos = 0
        if (version >= 3 && flags and 0x40 != 0 && body.size >= 4) {
            pos = if (version == 4) synchsafe(body, 0) else readU32(body, 0).toInt() + 4
        }
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        var wordTimed: String? = null
        val found = ArrayList<String>()
        while (pos >= 0 && pos + headLen <= body.size && body[pos].toInt() != 0) {
            val id = String(body, pos, idLen, Charsets.ISO_8859_1)
            val size = when (version) {
                2 -> ((body[pos + 3].toInt() and 0xFF) shl 16) or
                    ((body[pos + 4].toInt() and 0xFF) shl 8) or (body[pos + 5].toInt() and 0xFF)
                3 -> readU32(body, pos + 4).toInt()
                else -> synchsafe(body, pos + 4)
            }
            val frameFlags = if (version == 2) 0 else {
                ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF)
            }
            val start = pos + headLen
            if (size < 0 || start + size > body.size) break
            pos = start + size
            // Frame terkompresi atau terenkripsi tidak bisa dibaca di sini.
            val unreadable = when (version) {
                3 -> frameFlags and 0x00C0 != 0
                4 -> frameFlags and 0x000C != 0
                else -> false
            }
            if (unreadable || size < 2) continue
            var data = body.copyOfRange(start, start + size)
            if (version == 4) {
                if (frameFlags and 0x0001 != 0 && data.size > 4) data = data.copyOfRange(4, data.size)
                if (frameFlags and 0x0002 != 0) data = deunsync(data)
            }
            when (id) {
                "USLT", "ULT" -> id3Lyrics(data)?.let { found += it }
                "TXXX", "TXX" -> {
                    val (name, value) = id3UserText(data) ?: continue
                    if (value.isBlank()) continue
                    val key = name.uppercase()
                    when {
                        key == WORD_LYRICS_FIELD -> return value
                        key == "ELRC" -> if (wordTimed == null && hasStamps(value)) wordTimed = value
                        key in LYRIC_FIELDS -> found += value
                    }
                }
            }
        }
        // A tagger can write several lyric fields, and the first one is not
        // always the synced one: an unsynced copy has no stamps and parses to
        // nothing. Word-timed first, then any field with stamps, then plain.
        return (wordTimed ?: found.firstOrNull(::hasStamps) ?: found.firstOrNull())
            ?.takeIf { it.isNotBlank() }
    }

    /** `USLT`: encoding, language(3), descriptor, then the lyric text. */
    private fun id3Lyrics(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        val descEnd = id3Terminator(data, 4, encoding)
        val textStart = descEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (textStart >= data.size) return null
        return id3Decode(data, textStart, data.size, encoding).trimEnd('\u0000')
            .takeIf { it.isNotBlank() }
    }

    /** `TXXX`: encoding, description, then the value. */
    private fun id3UserText(data: ByteArray): Pair<String, String>? {
        val encoding = data[0].toInt() and 0xFF
        val nameEnd = id3Terminator(data, 1, encoding)
        val valueStart = nameEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (valueStart > data.size) return null
        val name = id3Decode(data, 1, nameEnd, encoding)
        val value = id3Decode(data, valueStart, data.size, encoding).trimEnd('\u0000')
        return name to value
    }

    private fun id3Terminator(data: ByteArray, from: Int, encoding: Int): Int {
        var i = from
        if (encoding == 1 || encoding == 2) {
            while (i + 1 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
                i += 2
            }
            return data.size
        }
        while (i < data.size && data[i].toInt() != 0) i++
        return i
    }

    private fun id3Decode(data: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (to <= from) return ""
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(data, from, to - from, charset)
    }

    private fun synchsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    /** Undoes ID3 unsynchronisation: every `FF 00` was `FF`. */
    private fun deunsync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---- Ogg Vorbis / Opus --------------------------------------------------

    /** Komentar Vorbis dari header `OpusTags` atau `\u0003vorbis` di halaman awal. */
    private fun ogg(bytes: ByteArray): String? {
        val until = minOf(bytes.size, 256 * 1024)
        bytes.indexOf(OPUS_TAGS, 0, until)?.let {
            return vorbisComment(bytes, it + OPUS_TAGS.size, bytes.size)
        }
        bytes.indexOf(VORBIS_COMMENT_HEADER, 0, until)?.let {
            return vorbisComment(bytes, it + VORBIS_COMMENT_HEADER.size, bytes.size)
        }
        return null
    }

    // ---- Sidecar files next to a local track --------------------------------

    /** `lagu.ttml`, `lagu.elrc` atau `lagu.lrc` di folder yang sama dengan audionya. */
    private fun localSidecar(context: Context, uri: Uri): String? {
        val audio = audioFile(context, uri) ?: return null
        val siblings = audio.parentFile?.listFiles() ?: return null
        val base = audio.nameWithoutExtension
        for (ext in LYRIC_SIDECAR_EXTS) {
            val file = siblings.firstOrNull {
                it.isFile && it.extension.equals(ext, ignoreCase = true) && it.nameWithoutExtension == base
            } ?: continue
            if (file.length() !in 1..MAX_TAG_BYTES.toLong()) continue
            val text = runCatching { file.readText() }.getOrNull()?.removePrefix("\uFEFF")
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun audioFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let(::File) else null }
        }.getOrNull()
    }

    private fun isTtml(text: String): Boolean {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return head.startsWith("<?xml") || head.startsWith("<tt")
    }

    // ---- FLAC ---------------------------------------------------------------

    /**
     * The `LYRICS` (or [WORD_LYRICS_FIELD]) comment out of the `VORBIS_COMMENT` block.
     *
     * Every length in the block is **little-endian** — it reuses Ogg Vorbis'
     * layout, which is the one part of FLAC that isn't big-endian.
     */
    private fun flac(bytes: ByteArray): String? {
        var pos = FLAC_MAGIC.size
        while (pos + 4 <= bytes.size) {
            val flags = bytes[pos].toInt() and 0xFF
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (start + length > bytes.size) return null
            if (flags and 0x7F == FLAC_VORBIS_COMMENT) {
                return vorbisComment(bytes, start, start + length)
            }
            if (flags and 0x80 != 0) return null
            pos = start + length
        }
        return null
    }

    private fun vorbisComment(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        fun u32(): Int? {
            if (pos + 4 > end) return null
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        val vendor = u32() ?: return null
        pos += vendor
        val count = u32() ?: return null
        var plain: String? = null
        var synced: String? = null
        repeat(count.coerceAtMost(4_096)) {
            val length = u32() ?: return synced ?: plain
            if (length < 0 || pos + length > end) return synced ?: plain
            val entry = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            val name = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            // This app's own field wins outright; the standard one is held in
            // case it turns out to be the only one there.
            if (name == WORD_LYRICS_FIELD && value.isNotBlank()) return value
            if (name in LYRIC_FIELDS && value.isNotBlank()) {
                if (plain == null) plain = value
                if (synced == null && hasStamps(value)) synced = value
            }
        }
        return synced ?: plain
    }

    // ---- Matroska / WebM ----------------------------------------------------

    /**
     * The `LYRICS` SimpleTag's string.
     *
     * Scanned for rather than walked down to: the tags a download writes are
     * appended after everything else (see `WebmTagger`), so reaching them
     * properly would mean parsing the whole Segment — every cluster of audio —
     * to arrive at the last few hundred bytes. The name is matched inside a
     * `TagName` element and the value read out of the `TagString` that follows,
     * so this is looking at tag structure rather than guessing at loose bytes.
     */
    private fun matroska(bytes: ByteArray): String? {
        var plain: String? = null
        for (name in listOf(WORD_LYRICS_FIELD, "LYRICS")) {
            val needle = name.toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                val at = bytes.indexOf(needle, from, bytes.size) ?: break
                from = at + needle.size
                // The name element's own header sits immediately in front of it:
                // id(2) + a one-byte length for a name this short.
                if (at < 3 || bytes[at - 3] != ID_TAGNAME[0] || bytes[at - 2] != ID_TAGNAME[1]) continue
                if ((bytes[at - 1].toInt() and 0x7F) != needle.size) continue
                val string = bytes.indexOf(ID_TAGSTRING, from, bytes.size) ?: continue
                val size = readVint(bytes, string + 2) ?: continue
                val valueAt = string + 2 + size.width
                if (size.value <= 0 || valueAt + size.value > bytes.size) continue
                val value = String(bytes, valueAt, size.value.toInt(), Charsets.UTF_8)
                if (value.isBlank()) continue
                if (name == WORD_LYRICS_FIELD) return value
                if (plain == null) plain = value
            }
        }
        return plain
    }

    private class Vint(val value: Long, val width: Int)

    /**
     * An EBML variable-length integer: the highest set bit of the first byte
     * gives the width, and the bits after it are the value.
     */
    private fun readVint(bytes: ByteArray, offset: Int): Vint? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > bytes.size) return null
        var value = (first and mask.inv() and 0xFF).toLong()
        for (i in 1 until width) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    // ---- Bytes --------------------------------------------------------------

    /** Reads up to [max] bytes, which is all of a normal file and a prefix of a huge one. */
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 1 shl 16))
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    /** `ftyp` at offset 4 is what says "this is an MP4" — there is no leading magic. */
    private fun ByteArray.isMp4(): Boolean =
        size > 12 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
            this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, from: Int, until: Int): Int? {
        if (needle.isEmpty()) return null
        val last = minOf(until, size) - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private const val FLAC_VORBIS_COMMENT = 4
    private val LYRIC_FIELDS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS", "LRC")
    private val LRC_STAMP = Regex("""\[\d{1,2}:\d{2}[.:]\d{2,3}]""")

    private fun hasStamps(text: String): Boolean = LRC_STAMP.containsMatchIn(text)
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val MATROSKA_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val DATA_ATOM = "data".toByteArray(Charsets.ISO_8859_1)
    private val LYR_ATOM = byteArrayOf(0xA9.toByte()) + "lyr".toByteArray(Charsets.ISO_8859_1)
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())
    private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)
    private val VORBIS_COMMENT_HEADER = byteArrayOf(0x03) + "vorbis".toByteArray(Charsets.US_ASCII)
    private val LYRIC_SIDECAR_EXTS = listOf("ttml", "elrc", "lrc")
}
