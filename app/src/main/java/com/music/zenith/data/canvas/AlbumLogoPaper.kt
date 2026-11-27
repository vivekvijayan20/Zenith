package com.music.zenith.data.canvas

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Makes the white paper of a raster album logo transparent while keeping the
 * ink colours, so a logo saved on an opaque white canvas sits on the dark
 * page instead of showing as a white box. Images without a white backdrop
 * (already transparent, or no near-white pixels at the border) pass through.
 */
object AlbumLogoPaperTransformation : Transformation() {

    override val cacheKey = "album-logo-paper-v1"

    /** Lowest channel at or above this is paper (fully transparent). */
    private const val PAPER_MIN = 250

    /** Lowest channel at or below this is ink (fully opaque). */
    private const val INK_MAX = 215

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width <= 0 || height <= 0) return input

        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        // Only act when the corners are opaque near-white: that is a paper
        // backdrop. A logo already on transparency is left alone.
        val corners = intArrayOf(
            pixels[0],
            pixels[width - 1],
            pixels[(height - 1) * width],
            pixels[height * width - 1],
        )
        if (corners.any { !isPaper(it) }) return input

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val inAlpha = pixel ushr 24
            if (inAlpha == 0) continue
            val lowest = minOf((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)
            val keep = when {
                lowest >= PAPER_MIN -> 0f
                lowest <= INK_MAX -> 1f
                else -> (PAPER_MIN - lowest).toFloat() / (PAPER_MIN - INK_MAX)
            }
            val outAlpha = (keep * inAlpha).toInt().coerceIn(0, 255)
            pixels[i] = (outAlpha shl 24) or (pixel and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun isPaper(pixel: Int): Boolean {
        if ((pixel ushr 24) < 250) return false
        val lowest = minOf((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)
        return lowest >= PAPER_MIN
    }
}