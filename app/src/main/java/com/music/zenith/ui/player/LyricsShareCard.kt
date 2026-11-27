package com.music.zenith.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.motion.lyrics.HeadOut
import com.motion.lyrics.MotionTuning
import com.motion.lyrics.MotionWord
import com.motion.lyrics.computeHead
import com.motion.lyrics.dampedStep
import com.motion.lyrics.smoothstep
import com.motion.lyrics.toMotionWords
import com.music.zenith.data.lyrics.CharGrowth
import com.music.zenith.data.lyrics.LyricLine
import androidx.core.content.res.ResourcesCompat
import com.music.zenith.R
import com.music.zenith.data.NerdStats
import com.music.zenith.data.settings.AppSettings
import com.music.zenith.ui.replay.Fonts
import com.music.zenith.ui.replay.drawArtwork
import com.music.zenith.ui.replay.drawBackdrop
import com.music.zenith.ui.replay.drawLogo
import com.music.zenith.ui.replay.ellipsised
import com.music.zenith.ui.replay.loadBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picked lines as one picture, drawn to a bitmap you can send.
 *
 * ## Why this is drawn rather than screenshotted
 *
 * The same reason the Replay is. A screenshot of the lyrics panel carries the
 * scrubber, the glow falloff, whichever lines happened to be on screen and the
 * status bar of that particular phone, so two people sharing the same verse
 * would send two different-looking pictures. A card laid out here is the verse,
 * the sleeve and this app's mark, and nothing else — and it is only as tall as
 * that verse needs, which holds it inside 1080×1920 (9:16): the shape a story
 * or a chat head expects. Keeping it there is the player's job, by refusing
 * more words at the pick rather than by shrinking them here.
 *
 * ## Why it isn't Compose
 *
 * `Bitmap` + `android.graphics.Canvas`, exactly as
 * [com.music.zenith.ui.replay.ReplayPoster] does it, and for the same
 * reasons written up there: Compose offscreen on minSdk 26 is a cascade of
 * hardware bitmaps and layers that fails differently per device, while a
 * software canvas draws identically from API 26 up. The backdrop, the logo, the
 * fonts and the sleeve are shared with the Replay by importing them rather than
 * by copying, so the two cards cannot drift apart.
 *
 * ## Fitting the type
 *
 * A passage can be one line or twenty, so both the size and the height are
 * chosen rather than fixed: the layout is retried down a fixed ladder of sizes
 * until the rows stop overflowing, and the card is then grown to exactly the
 * room those rows take. The bottom rung of that ladder is a floor rather than a
 * last resort — no card ever sets type smaller than it to squeeze a passage
 * in, so a passage too long for the frame lengthens the card instead of being
 * cut, or made small enough to hold a phone at arm's length to read.
 */
internal suspend fun renderLyricsShareCard(
    context: Context,
    card: LyricsShareRequest,
    /** Pad out to the full 1080×1920 frame, the passage centred in the room. */
    story: Boolean = false,
): Bitmap = lyricsCardPainter(context, card, story).render(null)

/**
 * The card laid out once, ready to be drawn at any moment of the song.
 *
 * Everything that never moves (backdrop, panel, song strip, brand) is drawn
 * here into one base picture; the passage is drawn over it per frame by
 * [LyricsCardPainter], so the share video and the sheet's preview can animate
 * the words without laying anything out again.
 */
internal suspend fun lyricsCardPainter(
    context: Context,
    card: LyricsShareRequest,
    story: Boolean = false,
    /** No backdrop: the card alone, to lay over a video. */
    transparent: Boolean = false,
    /** The Zenith mark at the top right; without it the passage moves up into its room. */
    showLogo: Boolean = true,
    /** The playing track's quality, small, under the artist; nothing for plain lossy audio. */
    showQuality: Boolean = true,
): LyricsCardPainter = withContext(Dispatchers.Default) {
    val type = Fonts(context)
    val cover = card.artworkUrl?.let { loadBitmap(context, it) }

    // ── Sizing ──────────────────────────────────────────────────────────────
    val headTop = if (showLogo) HEADER_BOTTOM + GAP_ABOVE_BODY else FOOTER_PAD
    val chrome = headTop + GAP_BELOW_BODY + FOOTER_THUMB + FOOTER_PAD
    val frameRoom = MAX_CARD_H - chrome
    val plan = fittestPlan(type, card.lines, frameRoom)
    // Flat layout, no panel: the words sit straight on the backdrop with the
    // song credit under them. A story pads the frame to 9:16 and keeps the
    // whole block centred in it.
    val naturalH = chrome + plan.content
    val cardH = if (story) maxOf(MAX_CARD_H, naturalH) else naturalH
    val shift = (cardH - naturalH) / 2f
    val bodyTop = headTop + shift
    val bodyBottom = bodyTop + plan.content
    val thumbY = bodyBottom + GAP_BELOW_BODY

    val bitmap = Bitmap.createBitmap(CARD_W, cardH.roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (!transparent) drawBackdrop(canvas, cover, 0f, cardH)

    // ── Brand ───────────────────────────────────────────────────────────────
    // Top right. The Z is drawn at the word's own cap height, sits on its
    // baseline (nudged down by LOGO_DROP) and stays close to the word.
    if (showLogo) {
        val brand = type.body(46f, 0xE6FFFFFF.toInt(), bold = true)
        val brandBaseline = HEADER_BASELINE + shift
        val capBounds = android.graphics.Rect().also { brand.getTextBounds("Z", 0, 1, it) }
        val logoH = capBounds.height().toFloat()
        val logoW = logoH * LOGO_ASPECT
        val brandWidth = logoW + LOGO_GAP + brand.measureText(BRAND)
        val brandLeft = CARD_W - CARD_MARGIN - brandWidth
        drawLogo(canvas, context, brandLeft + logoW, brandBaseline + LOGO_DROP, logoW, logoH)
        canvas.drawText(BRAND, brandLeft + logoW + LOGO_GAP, brandBaseline, brand)
    }

    // ── The passage ─────────────────────────────────────────────────────────
    // Only placed here; the painter draws each row, lit or not, per frame.
    val placed = ArrayList<PlacedRow>(plan.rows.size)
    var y = bodyTop
    for (row in plan.rows) {
        val baseline = y + row.gapBefore - row.paint.ascent() +
            if (row.isGap) row.paint.textSize * GAP_MARKER_OFFSET else 0f
        val x = if (row.centered) CARD_W / 2f else CARD_MARGIN
        placed += PlacedRow(row, x, baseline)
        y += row.gapBefore + row.height
    }

    // ── Song credit ─────────────────────────────────────────────────────────
    drawArtwork(canvas, cover, card.song.title, CARD_MARGIN, thumbY, FOOTER_THUMB, false, 0.12f)
    val textX = CARD_MARGIN + FOOTER_THUMB + 34f
    val room = CARD_W - CARD_MARGIN - textX
    val quality = if (showQuality) shareQualityOf(card) else null
    // With a badge under the artist, title and artist rise a little to make room.
    val lift = if (quality != null) CREDIT_LIFT else 0f
    val title = type.body(50f, Color.WHITE, bold = true)
    canvas.drawText(ellipsised(card.song.title, title, room), textX, thumbY + 74f - lift, title)
    val artist = type.body(42f, 0x99FFFFFF.toInt())
    canvas.drawText(ellipsised(card.song.artist, artist, room), textX, thumbY + 132f - lift, artist)
    if (quality != null) {
        drawQualityBadge(canvas, context, type, quality, textX, thumbY + FOOTER_THUMB, room)
    }

    // The lyric effect follows what the user has chosen in Settings.
    LyricsCardPainter(
        CardLayout(bitmap.width, bitmap.height, bitmap, placed),
        card.lines,
        liquid = AppSettings.liquidFlow.value,
        sparkle = AppSettings.lyricsSparkle.value,
    )
}

/**
 * The card's own backdrop alone, [height] tall at the card's width: what the
 * share video sits on when it has no animated cover to play under the words.
 */
internal suspend fun renderLyricsShareBackdrop(
    context: Context,
    card: LyricsShareRequest,
    height: Int,
): Bitmap = withContext(Dispatchers.Default) {
    val cover = card.artworkUrl?.let { loadBitmap(context, it) }
    Bitmap.createBitmap(CARD_W, height, Bitmap.Config.ARGB_8888).also {
        drawBackdrop(Canvas(it), cover, 0f, height.toFloat())
    }
}

// ── Animation ──────────────────────────────────────────────────────────────

/** A row of the passage, and where it sits on the card. */
internal class PlacedRow internal constructor(val row: Row, val x: Float, val baseline: Float)

private enum class ShareQuality { ATMOS, HI_RES, LOSSLESS }

/**
 * What the player is measuring on the track being shared: the decoder's own
 * figures, never what a source merely advertised. Null for lossy audio, or
 * when the measured stream belongs to a different track.
 */
private fun shareQualityOf(card: LyricsShareRequest): ShareQuality? {
    val stats = NerdStats.current.value ?: return null
    if (stats.mediaId != null && stats.mediaId != card.song.videoId) return null
    return when {
        stats.isDolbyAtmos -> ShareQuality.ATMOS
        stats.isHiRes -> ShareQuality.HI_RES
        stats.isLossless -> ShareQuality.LOSSLESS
        else -> null
    }
}

/** A small mark and label on [baseline], starting at [x], trimmed to [room]. */
private fun drawQualityBadge(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    quality: ShareQuality,
    x: Float,
    baseline: Float,
    room: Float,
) {
    val (iconRes, label) = when (quality) {
        ShareQuality.ATMOS -> R.drawable.ic_dolby_atmos to context.getString(R.string.dolby_atmos)
        ShareQuality.HI_RES -> R.drawable.ic_lossless_wave to context.getString(R.string.lyrics_share_hi_res)
        ShareQuality.LOSSLESS -> R.drawable.ic_lossless_wave to context.getString(R.string.lossless)
    }
    val paint = type.body(BADGE_TEXT, BADGE_COLOR)
    val cap = android.graphics.Rect().also { paint.getTextBounds("L", 0, 1, it) }.height().toFloat()
    var textX = x
    val icon = runCatching { ResourcesCompat.getDrawable(context.resources, iconRes, null) }.getOrNull()
    if (icon != null) {
        val h = cap * 0.9f
        val w = h * icon.intrinsicWidth / icon.intrinsicHeight.coerceAtLeast(1)
        val top = baseline - cap / 2f - h / 2f
        icon.setTint(BADGE_COLOR)
        icon.setBounds(Math.round(x), Math.round(top), Math.round(x + w), Math.round(top + h))
        icon.draw(canvas)
        textX = x + w + BADGE_GAP
    }
    canvas.drawText(ellipsised(label, paint, room - (textX - x)), textX, baseline, paint)
}

private const val CREDIT_LIFT = 14f
private const val BADGE_TEXT = 30f
private const val BADGE_GAP = 12f
private val BADGE_COLOR = 0x99FFFFFF.toInt()

internal class CardLayout internal constructor(
    val width: Int,
    val height: Int,
    val base: Bitmap,
    val rows: List<PlacedRow>,
)

/**
 * Draws the card at a moment of the song, the way the player's lyrics panel
 * draws the same lines at that moment.
 *
 * The arithmetic is the panel's own, read off the same [LyricLine]:
 * [LyricLine.revealedChars] for how far the sweep has got, [LyricLine.wordLift]
 * for the word rising as it is sung, [LyricLine.growingAt] for a held word
 * swelling letter by letter. The panel's distances become ratios of the type
 * size, so they hold at whatever size the card's ladder landed on. With no
 * position (the still card) every line is drawn lit.
 *
 * Not thread-safe: it reuses its paints. One instance per drawing thread.
 */
internal class LyricsCardPainter internal constructor(
    private val layout: CardLayout,
    private val lines: List<LyricsShareLine>,
    /** The user's LiquidFlow setting: the Motion effect instead of the default sweep. */
    private val liquid: Boolean = false,
    /** The user's sparkle setting, honoured while [liquid] is on. */
    private val sparkle: Boolean = true,
) {
    val width: Int get() = layout.width
    val height: Int get() = layout.height

    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scratch = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val growth = CharGrowth()
    private val headOut = HeadOut()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motionWords = HashMap<Int, List<MotionWord>>()
    private val clusterCache = HashMap<Row, IntArray>()
    private val follow = HashMap<Row, LiquidFollow>()

    /** Each line's place among the sung lines (gaps don't count), for the falloff. */
    private val rank = IntArray(lines.size).also { ranks ->
        var next = 0
        lines.forEachIndexed { index, line -> ranks[index] = if (line.isGap) -1 else next++ }
    }

    /** The card at [positionMs] (track time; null for the still card), into [into] when given. */
    fun render(positionMs: Long?, into: Bitmap? = null): Bitmap {
        val bitmap = into ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        drawOn(Canvas(bitmap), positionMs)
        return bitmap
    }

    /** Draws the card onto [canvas] at the card's own pixel size. */
    fun drawOn(canvas: Canvas, positionMs: Long?) {
        canvas.drawBitmap(layout.base, 0f, 0f, basePaint)
        val active = positionMs?.let(::activeLine) ?: -1
        for (placed in layout.rows) drawRow(canvas, placed, positionMs, active)
    }

    /** The line being sung at [positionMs]: the last one to have started, or the first. */
    private fun activeLine(positionMs: Long): Int {
        var first = -1
        var last = -1
        lines.forEachIndexed { index, line ->
            if (line.isGap) return@forEachIndexed
            val start = line.startMs ?: line.timing?.timeMs ?: return@forEachIndexed
            if (first < 0) first = index
            if (positionMs >= start) last = index
        }
        return if (last >= 0) last else first
    }

    private fun drawRow(canvas: Canvas, placed: PlacedRow, positionMs: Long?, active: Int) {
        val row = placed.row
        val line = lines.getOrNull(row.line)
        if (positionMs == null || row.isGap || line == null || active < 0) {
            canvas.drawText(row.text, placed.x, placed.baseline, row.paint)
            return
        }
        val timing = line.timing
        val distance = kotlin.math.abs(rank[row.line] - rank[active]).coerceAtMost(LINE_FALLOFF.lastIndex)
        val alpha = row.paint.alpha * LINE_FALLOFF[distance]
        val start = line.startMs ?: timing?.timeMs ?: 0L
        val swept = timing != null && timing.isWordSynced && !row.rtl

        // LiquidFlow on: the same Motion effect the lyrics panel draws.
        if (liquid && !row.isSub && !row.rtl && timing != null && timing.isWordSynced && timing.timingSource == null) {
            val words = motionWords.getOrPut(row.line) { timing.toMotionWords() }
            if (words.isNotEmpty()) {
                drawLiquid(canvas, placed, positionMs, timing, words, alpha)
                return
            }
        }

        if (!swept) {
            // No word timings (or a script the sweep can't follow): the line
            // lights whole the moment it starts, as the panel's does.
            val lit = if (positionMs >= start) 1f else UNSUNG_ALPHA
            scratch.set(row.paint)
            scratch.alpha = (alpha * lit).roundToInt()
            canvas.drawText(row.text, placed.x, placed.baseline, scratch)
            return
        }
        timing!!
        // How far the sweep has got, in this row's own characters.
        val revealedInLine = if (row.isSub) {
            val lead = timing.text.length.coerceAtLeast(1)
            (timing.revealedChars(positionMs) / lead).coerceIn(0f, 1f) * (line.subText?.length ?: 0)
        } else {
            timing.revealedChars(positionMs)
        }
        val revealed = (revealedInLine - row.start).coerceIn(0f, row.text.length.toFloat())
        val sung = positionMs >= timing.endMs
        val dim = if (sung) 1f else UNSUNG_ALPHA
        val lifts = !row.isSub

        // The unsung copy underneath, then the lit one over it up to the sweep.
        scratch.set(row.paint)
        scratch.alpha = (alpha * dim).roundToInt()
        drawPieces(canvas, placed, scratch, positionMs, timing, lifts, bloom = false)
        if (revealed <= 0f || (sung && revealed >= row.text.length)) return

        scratch.alpha = alpha.roundToInt()
        if (revealed >= row.text.length) {
            drawPieces(canvas, placed, scratch, positionMs, timing, lifts, bloom = true)
            return
        }
        val size = row.paint.textSize
        val edge = placed.x + sweepX(row, revealed, if (row.isSub) emptyList() else timing.sweepSpans)
        // The feather runs ahead of the sweep, into what is still to come.
        val feather = minOf(size * FEATHER_RATIO, edge - placed.x)
        val top = placed.baseline + row.paint.ascent() - size * HEADROOM_RATIO
        val bottom = placed.baseline + row.paint.descent() + size * 0.1f
        val save = canvas.saveLayer(0f, top, layout.width.toFloat(), bottom, null)
        canvas.clipRect(0f, top, edge + feather, bottom)
        drawPieces(canvas, placed, scratch, positionMs, timing, lifts, bloom = true)
        if (feather > 0.5f) {
            fade.shader = LinearGradient(
                edge, 0f, edge + feather, 0f,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, top, layout.width.toFloat(), bottom, fade)
        }
        canvas.restoreToCount(save)
    }

    /**
     * The row's text with each word at its lift, and a held word letter by
     * letter at its swell, with (when [bloom]) the glow its letters give off.
     */
    private fun drawPieces(
        canvas: Canvas,
        placed: PlacedRow,
        paint: Paint,
        positionMs: Long,
        timing: LyricLine,
        lifts: Boolean,
        bloom: Boolean,
    ) {
        val row = placed.row
        val text = row.text
        if (!lifts) {
            canvas.drawText(text, placed.x, placed.baseline, paint)
            return
        }
        val size = paint.textSize
        val rise = size * RISE_RATIO
        val rowStart = row.start
        val rowEnd = rowStart + text.length
        fun run(from: Int, to: Int, dy: Float) {
            if (to <= from) return
            canvas.drawRunOf(text, from, to, placed.x + advanceAt(paint, text, from), placed.baseline + dy, paint)
        }
        var cursor = 0
        timing.wordSpans.forEachIndexed { index, span ->
            val from = maxOf(span.first, rowStart) - rowStart
            val to = minOf(span.last + 1, rowEnd) - rowStart
            if (to <= from) return@forEachIndexed
            run(cursor, from, 0f)
            val grow = timing.growingAt(index)
            if (grow != null && positionMs >= grow.startMs && positionMs <= grow.restsAtMs) {
                for (k in from until to) {
                    val letter = rowStart + k - span.first
                    grow.sampleInto(letter, positionMs, growth)
                    val left = placed.x + advanceAt(paint, text, k)
                    val w = advanceAt(paint, text, k + 1) - advanceAt(paint, text, k)
                    val cx = left + w / 2f + growth.shift * size
                    val y = placed.baseline - growth.rise * rise
                    canvas.save()
                    canvas.scale(growth.scale, growth.scale, cx, y)
                    if (bloom && growth.bloom > 0.01f) {
                        glow.set(paint)
                        glow.alpha = (paint.alpha * growth.bloom.coerceAtMost(1f)).roundToInt()
                        glow.maskFilter = BlurMaskFilter(size * GLOW_RATIO, BlurMaskFilter.Blur.NORMAL)
                        canvas.drawRunOf(text, k, k + 1, cx - w / 2f, y, glow)
                    }
                    canvas.drawRunOf(text, k, k + 1, cx - w / 2f, y, paint)
                    canvas.restore()
                }
            } else {
                run(from, to, -timing.wordLift(index, positionMs) * rise)
            }
            cursor = to
        }
        run(cursor, text.length, 0f)
    }

    /** Start and end offsets of each visible grapheme in [row], flattened. */
    private fun clustersOf(row: Row): IntArray = clusterCache.getOrPut(row) {
        val out = ArrayList<Int>()
        val breaker = android.icu.text.BreakIterator.getCharacterInstance()
        breaker.setText(row.text)
        var s = breaker.first()
        var e = breaker.next()
        while (e != android.icu.text.BreakIterator.DONE) {
            if (!row.text[s].isWhitespace()) {
                out.add(s)
                out.add(e)
            }
            s = e
            e = breaker.next()
        }
        out.toIntArray()
    }

    /**
     * The LiquidFlow effect on a canvas, letter by letter: rise, swell, the soft
     * glow behind the karaoke head, the feathered sweep and (when enabled) the
     * sparkle — the lyrics panel's own numbers, taken at [pos] directly.
     */
    private fun drawLiquid(
        canvas: Canvas,
        placed: PlacedRow,
        pos: Long,
        timing: LyricLine,
        words: List<MotionWord>,
        alpha: Float,
    ) {
        val row = placed.row
        val paint = row.paint
        val text = row.text
        val size = paint.textSize
        val bounds = clustersOf(row)
        val n = bounds.size / 2
        if (n == 0) return
        computeHead(words, pos, headOut)
        val head = headOut.head
        var sung = headOut.sung
        val riseDist = size * MotionTuning.RiseDistanceEm
        val top = placed.baseline + paint.ascent()
        val bottom = placed.baseline + paint.descent()
        val midY = (top + bottom) / 2f
        val activeAlpha = alpha.roundToInt().coerceIn(0, 255)
        val inactiveAlpha = (alpha * (if (pos >= timing.endMs) 1f else UNSUNG_ALPHA)).roundToInt().coerceIn(0, 255)
        val rgb = paint.color and 0x00FFFFFF
        val firstStart = words.first().startMs

        val rise = FloatArray(n)
        val swell = FloatArray(n)
        val lefts = FloatArray(n)
        val rights = FloatArray(n)
        val wordAt = IntArray(n)
        for (k in 0 until n) {
            val s = bounds[k * 2]
            val e = bounds[k * 2 + 1]
            lefts[k] = placed.x + advanceAt(paint, text, s)
            rights[k] = placed.x + advanceAt(paint, text, e)
            val c = (row.start + s).toFloat()
            val wi = words.indexOfFirst { c >= it.charStart && c < it.charEnd }
            wordAt[k] = wi
            var riseTarget = 1f
            var swellTarget = 0f
            if (wi >= 0) {
                val w = words[wi]
                val through = (head - w.charStart) / (w.charEnd - w.charStart).coerceAtLeast(1).toFloat()
                val wordRise = smoothstep(through / MotionTuning.RiseCompleteAt)
                val letterRise = if (head >= w.charEnd) 1f else
                    smoothstep((head - c + MotionTuning.LetterRiseLeadChars) / MotionTuning.LetterRiseSpanChars)
                riseTarget = wordRise + (letterRise - wordRise) * MotionTuning.LetterRiseShare
                val hold = smoothstep(((w.endMs - w.startMs) - MotionTuning.HoldMinMs) / MotionTuning.HoldRangeMs)
                val tail = if (pos > w.endMs) (pos - w.endMs) / 1000f * MotionTuning.SwellTailCharsPerSec else 0f
                val sw = (head - c + MotionTuning.LetterSwellLeadChars + tail) / MotionTuning.LetterSwellSpanChars
                swellTarget = hold *
                    smoothstep(sw / MotionTuning.SwellAttackFraction) *
                    smoothstep((1f - sw) / MotionTuning.SwellReleaseFraction)
            }
            if (pos < firstStart) swellTarget = 0f
            rise[k] = riseTarget
            swell[k] = swellTarget
        }

        // Follow the targets with the panel's own critically damped springs, so
        // the rise, swell and glow ease instead of jumping to each frame's value.
        // A seek or a cut to another stretch of the track snaps instead.
        val state = follow.getOrPut(placed.row) { LiquidFollow(n) }
        val dtMs = if (state.lastPos == Long.MIN_VALUE) Long.MAX_VALUE else pos - state.lastPos
        val snap = dtMs < 0L || dtMs > FOLLOW_SNAP_MS
        if (dtMs != 0L) {
            val dt = (dtMs / 1000f).coerceIn(0.001f, 0.25f)
            for (k in 0 until n) {
                if (snap) {
                    state.rise[k] = rise[k]; state.riseVel[k] = 0f
                    state.swell[k] = swell[k]; state.swellVel[k] = 0f
                } else {
                    dampedStep(state.rise, state.riseVel, k, rise[k], MotionTuning.RiseFollowSec, dt)
                    dampedStep(state.swell, state.swellVel, k, swell[k], MotionTuning.SwellFollowSec, dt)
                }
            }
            if (snap) {
                state.bloom[0] = sung; state.bloomVel[0] = 0f
            } else {
                dampedStep(state.bloom, state.bloomVel, 0, sung, MotionTuning.BloomFollowSec, dt)
            }
            state.lastPos = pos
        }
        for (k in 0 until n) {
            rise[k] = state.rise[k]
            swell[k] = state.swell[k]
        }
        sung = state.bloom[0]

        // Glow first, under the letters, fading continuously.
        val glowR = size * MotionTuning.GlowRadiusEm
        for (k in 0 until n) {
            val swc = swell[k].coerceIn(0f, 1f)
            val c = (row.start + bounds[k * 2]).toFloat()
            val prox = 1f - smoothstep(kotlin.math.abs(head - c - 1f) / MotionTuning.GlowSpanChars)
            val a = (sung * prox * (0.35f + 0.65f * swc) * MotionTuning.GlowAlphaScale * (activeAlpha / 255f))
                .coerceIn(0f, 1f)
            if (a < 0.004f) continue
            val cx = (lefts[k] + rights[k]) / 2f
            val cy = midY + (1f - rise[k]) * riseDist
            val rad = glowR * (1f + 0.15f * swc)
            halo.shader = android.graphics.RadialGradient(
                cx, cy, rad,
                intArrayOf(
                    ((a * 255).roundToInt() shl 24) or rgb,
                    ((a * 0.45f * 255).roundToInt() shl 24) or rgb,
                    rgb,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, rad, halo)
        }
        halo.shader = null

        val feather = (size * MotionTuning.SweepFeatherEm).coerceAtLeast(1f)
        for (k in 0 until n) {
            val s = bounds[k * 2]
            val e = bounds[k * 2 + 1]
            val left = lefts[k]
            val w = rights[k] - left
            val dy = (1f - rise[k]) * riseDist
            val scale = 1f + MotionTuning.SwellScale * swell[k]
            val px = left + w / 2f
            val dist = (head - (row.start + s)) / (e - s).coerceAtLeast(1)
            val wi = wordAt[k]
            val wordDone = wi >= 0 && head >= words[wi].charEnd - 0.001f

            canvas.save()
            canvas.translate(0f, dy)
            canvas.scale(scale, scale, px, midY)
            scratch.set(paint)
            scratch.shader = null
            scratch.alpha = inactiveAlpha
            canvas.drawRunOf(text, s, e, left, placed.baseline, scratch)

            val hx = dist * w
            val front = feather * 0.6f
            val back = feather * 0.4f
            // Before the line's first word the head rests on its first letter;
            // nothing may be lit yet, or that letter glows ahead of its turn.
            val waiting = pos < firstStart
            val reached = !waiting && hx + front > 0f
            val settled = !waiting && hx - back >= w
            if (settled || wordDone) {
                scratch.alpha = activeAlpha
                canvas.drawRunOf(text, s, e, left, placed.baseline, scratch)
            } else if (reached) {
                fun tone(a: Float) = ((activeAlpha * a).roundToInt() shl 24) or rgb
                scratch.shader = LinearGradient(
                    left + hx - back, 0f, left + hx + front, 0f,
                    intArrayOf(tone(1f), tone(0.96f), tone(0.72f), tone(0.30f), tone(0.07f), tone(0f)),
                    floatArrayOf(0f, 0.30f, 0.55f, 0.78f, 0.92f, 1f),
                    Shader.TileMode.CLAMP,
                )
                scratch.alpha = 255
                canvas.drawRunOf(text, s, e, left, placed.baseline, scratch)
                scratch.shader = null
            }
            canvas.restore()

            if (sparkle && swell[k] > 0.01f) {
                drawSparkles(canvas, row.start + k, px, top + dy, w, size, pos / 1000f, rgb, smoothstep(swell[k] / 0.35f))
            }
        }
    }

    /** The glimmers around a held letter, drifting up and fading. */
    private fun drawSparkles(
        canvas: Canvas,
        index: Int,
        cx: Float,
        top: Float,
        w: Float,
        size: Float,
        timeSec: Float,
        rgb: Int,
        strength: Float,
    ) {
        for (k in 0 until MotionTuning.ParticleCount) {
            val seed = index * 31 + k * 17
            val phase = (timeSec * MotionTuning.ParticleSpeed + hash01(seed)) % 1f
            val x = cx + (hash01(seed + 7) - 0.5f) * (w + size * 0.5f)
            val y = top + size * 0.2f - phase * size * MotionTuning.ParticleRiseEm
            val a = kotlin.math.sin(Math.PI * phase).toFloat() * strength
            val r = size * 0.028f * (0.6f + hash01(seed + 13))
            halo.shader = null
            halo.color = (((a * 0.12f).coerceIn(0f, 1f) * 255).roundToInt() shl 24) or rgb
            canvas.drawCircle(x, y, r * 2.6f, halo)
            halo.color = (((a * 0.75f).coerceIn(0f, 1f) * 255).roundToInt() shl 24) or rgb
            canvas.drawCircle(x, y, r, halo)
        }
    }

    /** Where [chars] characters into [row] falls, from the row's left edge. */
    private fun sweepX(row: Row, chars: Float, spans: List<IntRange>): Float {
        val text = row.text
        val paint = row.paint
        val at = row.start + chars
        // Within a timed piece the edge crosses its width at a steady pace.
        val piece = spans.firstOrNull { at >= it.first && at < it.last + 1 }
        if (piece != null && piece.first >= row.start && piece.last + 1 <= row.start + text.length) {
            val from = paint.measureText(text, 0, piece.first - row.start)
            val to = paint.measureText(text, 0, piece.last + 1 - row.start)
            return from + (to - from) * (at - piece.first) / (piece.last + 1 - piece.first)
        }
        // A space between words, or a piece broken over a wrap: letter by letter.
        val k = chars.toInt().coerceIn(0, text.length)
        val here = paint.measureText(text, 0, k)
        val next = paint.measureText(text, 0, (k + 1).coerceAtMost(text.length))
        return here + (next - here) * (chars - k)
    }
}

// The panel's animation numbers as ratios of the type size (its 34sp headline).
private const val UNSUNG_ALPHA = 0.45f
private val LINE_FALLOFF = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
private const val FEATHER_RATIO = 30f / 34f
private const val RISE_RATIO = 2f / 34f
private const val HEADROOM_RATIO = 0.35f
private const val GLOW_RATIO = 6f / 34f

/** A jump in track time longer than this is a seek or a cut, not a frame step. */
private const val FOLLOW_SNAP_MS = 250L

/** The eased rise, swell and glow of one row, carried from frame to frame. */
private class LiquidFollow(n: Int) {
    val rise = FloatArray(n)
    val riseVel = FloatArray(n)
    val swell = FloatArray(n)
    val swellVel = FloatArray(n)
    val bloom = FloatArray(1)
    val bloomVel = FloatArray(1)
    var lastPos = Long.MIN_VALUE
}

/** A repeatable pseudo-random 0..1 from [n], so a sparkle sits where it did last frame. */
private fun hash01(n: Int): Float {
    var x = n * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFF) / 65535f
}

/**
 * The pen position at [offset], shaped within the whole [text] so the kerning
 * with the letter that follows is counted. Measuring a prefix
 * (`measureText(text, 0, offset)`) drops that pair, which pushed the "o" of
 * "You" into the "u" when letters are drawn one by one.
 */
private fun advanceAt(paint: Paint, text: String, offset: Int): Float =
    if (offset <= 0) 0f
    else paint.getRunAdvance(text, 0, text.length, 0, text.length, false, offset.coerceAtMost(text.length))

/** Draws [from, to) of [text] shaped in the context of the whole line, so a lone letter keeps its place. */
private fun Canvas.drawRunOf(text: String, from: Int, to: Int, x: Float, y: Float, paint: Paint) =
    drawTextRun(text, from, to, 0, text.length, x, y, false, paint)

private fun isRtl(text: String): Boolean =
    !java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()

// ── Fitting ────────────────────────────────────────────────────────────────

internal class Row internal constructor(
    val text: String,
    val paint: Paint,
    val height: Float,
    val gapBefore: Float,
    /** Drawn centred rather than off the left edge — the gap marker. */
    val centered: Boolean = false,
    val isGap: Boolean = false,
    /** Which of the request's lines this row belongs to. */
    val line: Int = -1,
    /** Where the row starts in its line's text (or in its sub-line's, for [isSub]). */
    val start: Int = 0,
    /** The translation or romanization under the line, rather than the line itself. */
    val isSub: Boolean = false,
    /** Right-to-left text, which lights whole rather than sweeping. */
    val rtl: Boolean = false,
)

/** The rows at one size, with everything needed to place them. */
private class Plan(val rows: List<Row>, val ascent: Float) {
    /** Distance from the top of the first line to the bottom of the last. */
    val content: Float
        get() = ascent + rows.fold(0f) { total, row -> total + row.gapBefore + row.height }
}

/**
 * The largest size on [FIT_LADDER] whose rows still fit in [room], or the
 * bottom rung when none of them do.
 *
 * The bottom rung is a floor rather than a last resort: under it the type stops
 * being worth sharing, so the frame grows to hold the words instead of the
 * words shrinking to hold the frame. That the player budgets characters at the
 * pick is what keeps this branch from ever being reached in practice.
 */
private fun fittestPlan(
    type: Fonts,
    lines: List<LyricsShareLine>,
    room: Float,
): Plan {
    for (size in FIT_LADDER) {
        val plan = planAt(type, lines, size)
        if (plan.content <= room) return plan
    }
    return planAt(type, lines, FIT_LADDER.last())
}

/**
 * The rows at one [size], set in the face the lyrics panel draws with.
 *
 * SF Pro Display Heavy with the panel's tight tracking and its line advance,
 * carried over as the same ratios the panel's 34sp headline uses — so the card
 * reads as a still of that panel rather than as a different app quoting it.
 * Compose's tracking is absolute and `Paint`'s is a fraction of the size, hence
 * the divisions: -0.7 over 34 for the words, -0.7 over 20 for the smaller line
 * the panel hangs underneath them.
 */
private fun planAt(type: Fonts, lines: List<LyricsShareLine>, size: Float): Plan {
    val primary = type.heading(size, Color.WHITE).apply { letterSpacing = TRACKING }
    val subSize = (size * SUB_RATIO).coerceAtLeast(SUB_FLOOR)
    val secondary = type.heading(subSize, SUB_COLOR).apply { letterSpacing = SUB_TRACKING }

    val rows = mutableListOf<Row>()
    lines.forEachIndexed { index, line ->
        if (line.isGap) {
            // The skipped lines, as "(...)" — the conventional editorial mark
            // for an omission. Hung off the left edge with the words, smaller
            // and a dim grey. Keep its row compact so the marker follows the
            // preceding lyric closely; the regular gap on the next lyric line
            // provides the separation below it.
            val marker = type.body(size * 0.65f, 0x66FFFFFF.toInt())
            val markerHeight = marker.descent() - marker.ascent()
            rows += Row("(...)", marker, markerHeight, 0f, isGap = true, line = index)
            return@forEachIndexed
        }
        var firstOfLine = true
        fun add(text: String, paint: Paint, height: Float, start: Int, isSub: Boolean, rtl: Boolean) {
            // One verse line is one paragraph: its first row carries the space
            // above it and its wraps sit tight underneath, so a phrase reads as
            // a block rather than as a list of separate sentences.
            val gap = when {
                rows.isEmpty() -> 0f
                firstOfLine && index > 0 -> size * 0.55f
                else -> 0f
            }
            rows += Row(text, paint, height, gap, line = index, start = start, isSub = isSub, rtl = rtl)
            firstOfLine = false
        }
        // Each row remembers where it starts in its line, so the sweep (which
        // counts characters of the whole line) can be found within it.
        fun addRows(source: String, paint: Paint, height: Float, isSub: Boolean) {
            val rtl = isRtl(source)
            var cursor = 0
            wrap(source, paint, CARD_CONTENT_W).forEach { piece ->
                val at = source.indexOf(piece, cursor).takeIf { it >= 0 } ?: cursor
                cursor = at + piece.length
                add(piece, paint, height, at, isSub, rtl)
            }
        }
        addRows(line.text, primary, size * LEAD, isSub = false)
        val sub = line.subText
        if (!sub.isNullOrBlank() && sub != line.text) {
            addRows(sub, secondary, subSize * SUB_LEAD, isSub = true)
        }
    }
    return Plan(rows, -primary.ascent())
}

// ── Text ───────────────────────────────────────────────────────────────────

/**
 * Greedy wrap at [width], breaking wherever the line has to.
 *
 * Not a plain word-wrap: Japanese, Chinese and Korean lyrics carry no spaces at
 * all, and a wrapper that only split on spaces would hand the whole verse to
 * `drawText` and let it run off the card. `breakText` finds the character that
 * fits, and only then is the break walked back to a space — so scripts that keep
 * their words apart lose nothing, and scripts that don't still get several lines.
 */
private fun wrap(text: String, paint: Paint, width: Float): List<String> {
    val source = text.trim()
    if (source.isEmpty()) return listOf("")

    val out = mutableListOf<String>()
    var rest = source
    while (rest.isNotEmpty()) {
        val fit = paint.breakText(rest, true, width, null)
        if (fit <= 0) {
            out += rest
            break
        }
        // Walk back to the last space inside the fit, but only when something
        // still has to follow it — otherwise the fit is the whole string and
        // cutting at the space would drop the final word.
        val space = rest.lastIndexOf(' ', fit - 1)
        val take = if (space > 0 && fit < rest.length) space else fit
        if (take <= 0) {
            out += rest
            break
        }
        out += rest.substring(0, take).trimEnd()
        rest = rest.substring(take).trimStart()
    }
    return out.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
}

// ── Card geometry ──────────────────────────────────────────────────────────

private const val CARD_W = 1080

/**
 * 9:16 — a ceiling on the height, not the height itself. The card is grown to
 * its passage and only ever comes out this tall when the passage demands it.
 */
private const val MAX_CARD_H = 1920f

/** Side margin of the passage and of the song credit. */
private const val CARD_MARGIN = 72f
private const val CARD_CONTENT_W = CARD_W - CARD_MARGIN * 2

/** Gap between the Z and the word, and how far the Z is lowered onto the word's baseline. */
private const val LOGO_GAP = 12f
private const val LOGO_DROP = 2f
private const val BRAND = "Music"
private const val LOGO_ASPECT = 730f / 484f

/** The mark's row at the top right, and the air around the passage. */
private const val HEADER_BASELINE = 132f
private const val HEADER_BOTTOM = 170f
private const val GAP_ABOVE_BODY = 20f
private const val GAP_BELOW_BODY = 20f

/** The song credit at the foot: the sleeve square and the margin under it. */
private const val FOOTER_THUMB = 160f
private const val FOOTER_PAD = 96f

/** Smallest the translation line is allowed to get before it stops being legible. */
private const val SUB_FLOOR = 24f

/** Small visual correction so the omission marker sits below the line above. */
private const val GAP_MARKER_OFFSET = 0.18f

/**
 * The sizes tried, largest first.
 *
 * A ladder rather than a search: a dozen candidates is nothing to lay out, and
 * stepping by a visible amount keeps two cards with adjacent line counts from
 * coming out nearly the same size and reading as a mistake.
 *
 * The last rung doubles as the floor — see [fittestPlan] for why the frame is
 * what gives way under it rather than the type.
 */
private val FIT_LADDER = floatArrayOf(80f, 72f, 64f, 56f, 50f, 44f, 38f, 32f)

// ── The panel's type, as ratios ────────────────────────────────────────────
// Every number here is one the lyrics panel uses, divided by the size it uses
// it at so it holds at whatever size the ladder lands on.

/** Tracking of the panel's headline: -0.7sp against its 34sp. */
private const val TRACKING = -0.7f / 34f

/** The same -0.7sp, against the 20sp it sets the line underneath in. */
private const val SUB_TRACKING = -0.7f / 20f

/** Line advance: 41sp over 34sp for the words, 25sp over 20sp for the sub. */
private const val LEAD = 41f / 34f
private const val SUB_LEAD = 25f / 20f

/** The sub-line against the words above it, and the white it is drawn in. */
private const val SUB_RATIO = 20f / 34f
private val SUB_COLOR = 0xD9FFFFFF.toInt()
