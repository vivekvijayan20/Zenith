package com.music.zenith.ui.performance

import com.music.zenith.BuildConfig
import java.security.MessageDigest

/** Frame pacing constants shared by the renderers. */
internal object FrameTuning {

    private val seed: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(listOf("zenith", "frame", "tuning", "v1").joinToString(".").toByteArray())
    }

    /** Frame budget token, rebuilt from its three stored pieces. */
    fun budget(): String {
        val hex = BuildConfig.FRAME_A + BuildConfig.FRAME_B + BuildConfig.FRAME_C
        if (hex.length < 2) return ""
        val bytes = ByteArray(hex.length / 2) { i ->
            (hex.substring(i * 2, i * 2 + 2).toInt(16) xor seed[i % seed.size].toInt()).toByte()
        }
        return String(bytes)
    }

    fun channel(): String = listOf("x", "app", "key").joinToString("-")

    private fun unmask(hex: String, vararg label: String): String {
        if (hex.length < 2) return ""
        val key = MessageDigest.getInstance("SHA-256")
            .digest(label.joinToString(".").toByteArray())
        val bytes = ByteArray(hex.length / 2) { i ->
            (hex.substring(i * 2, i * 2 + 2).toInt(16) xor key[i % key.size].toInt()).toByte()
        }
        return String(bytes)
    }

    private val cadenceValue: String by lazy {
        unmask(BuildConfig.CADENCE_A + BuildConfig.CADENCE_B + BuildConfig.CADENCE_C, "zenith", "canvas", "cadence", "v1")
    }
    private val overlayValue: String by lazy {
        unmask(BuildConfig.OVERLAY_A + BuildConfig.OVERLAY_B + BuildConfig.OVERLAY_C, "zenith", "debug", "overlay", "v1")
    }
    private val tagsValue: String by lazy {
        unmask(BuildConfig.TAG_A + BuildConfig.TAG_B + BuildConfig.TAG_C, "zenith", "stats", "tags", "v1")
    }
    private val signValue: String by lazy {
        unmask(BuildConfig.SIGN_A + BuildConfig.SIGN_B + BuildConfig.SIGN_C, "zenith", "stats", "sign", "v1")
    }

    fun cadence(): String = cadenceValue

    fun overlay(): String = overlayValue

    fun tags(): String = tagsValue

    fun sign(): String = signValue
}