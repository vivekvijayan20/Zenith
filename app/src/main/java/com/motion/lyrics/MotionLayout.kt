package com.motion.lyrics

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection

/** Geometri per huruf, di-cache sekali per hasil layout teks. */
internal class MotionLayout(
    val result: TextLayoutResult,
    val count: Int,
    val charIndex: IntArray,
    val charLen: IntArray,
    val wordIndex: IntArray,
    val lineIndex: IntArray,
    val left: FloatArray,
    val top: FloatArray,
    val right: FloatArray,
    val bottom: FloatArray,
    val glyphY: FloatArray,
    val rtl: BooleanArray,
    val glyphs: Array<TextLayoutResult>
)

internal fun buildMotionLayout(
    result: TextLayoutResult,
    measurer: TextMeasurer,
    style: TextStyle,
    words: List<MotionWord>
): MotionLayout {
    val text = result.layoutInput.text.text
    val wordOf = IntArray(text.length) { -1 }
    words.forEachIndexed { wi, w ->
        for (c in w.charStart.coerceAtLeast(0) until w.charEnd.coerceAtMost(text.length)) wordOf[c] = wi
    }
    // Pecah per grapheme cluster (bukan per code point) supaya aksara dengan tanda
    // gabung (Tamil, Hindi, Arab, dll) tidak terpisah menjadi lingkaran titik dan bertumpuk.
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    val bi = android.icu.text.BreakIterator.getCharacterInstance()
    bi.setText(text)
    var cs = bi.first()
    var ce = bi.next()
    while (ce != android.icu.text.BreakIterator.DONE) {
        if (!text[cs].isWhitespace()) {
            starts.add(cs)
            ends.add(ce)
        }
        cs = ce
        ce = bi.next()
    }
    val n = starts.size
    val charIndex = IntArray(n); val charLen = IntArray(n)
    val wordIndex = IntArray(n); val lineIndex = IntArray(n)
    val l = FloatArray(n); val t = FloatArray(n); val r = FloatArray(n); val b = FloatArray(n)
    val gy = FloatArray(n); val rtl = BooleanArray(n)
    val glyphs = ArrayList<TextLayoutResult>(n)
    for (k in 0 until n) {
        val c = starts[k]
        val e = ends[k]
        val line = result.getLineForOffset(c)
        // Gabungkan kotak semua offset di dalam cluster (vowel sign / pulli ikut).
        val first = result.getBoundingBox(c)
        var bl = first.left; var bt = first.top
        var br = first.right; var bb = first.bottom
        for (o in c + 1 until e) {
            val bx = result.getBoundingBox(o)
            if (bx.right - bx.left <= 0f) continue
            if (bx.left < bl) bl = bx.left
            if (bx.right > br) br = bx.right
            if (bx.top < bt) bt = bx.top
            if (bx.bottom > bb) bb = bx.bottom
        }
        val g = measurer.measure(
            AnnotatedString(text.substring(c, e)),
            style = style, softWrap = false, maxLines = 1
        )
        charIndex[k] = c; charLen[k] = (e - c).coerceAtLeast(1)
        wordIndex[k] = wordOf[c]; lineIndex[k] = line
        l[k] = bl; t[k] = bt; r[k] = br; b[k] = bb
        gy[k] = result.getLineBaseline(line) - g.firstBaseline
        rtl[k] = result.getBidiRunDirection(c) == ResolvedTextDirection.Rtl
        glyphs.add(g)
    }
    return MotionLayout(
        result, n, charIndex, charLen, wordIndex, lineIndex, l, t, r, b, gy, rtl, glyphs.toTypedArray()
    )
}