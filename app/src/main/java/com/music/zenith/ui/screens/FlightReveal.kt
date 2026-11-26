package com.music.zenith.ui.screens

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.zenith.ui.components.LocalCoverVisibilityScope
import com.music.zenith.ui.components.LocalPageRevisit
import com.music.zenith.ui.components.coverFlightPending
import com.music.zenith.ui.components.enterReveal

/**
 * [enterReveal], plus (bila [afterFlight]) menunggu cover terbang selesai dan
 * overlay-nya lepas. Cover terbang digambar di atas seluruh halaman, jadi teks
 * yang muncul selama penerbangan tampil di belakangnya lalu meloncat ke depan
 * saat cover mendarat. Dengan menunggu, teks langsung muncul di depan cover.
 * Halaman yang dibuka tanpa penerbangan tidak menunggu apa pun.
 */
@Composable
internal fun Modifier.flightReveal(
    delayMs: Int = 0,
    rise: Dp = 14.dp,
    afterFlight: Boolean = false,
): Modifier {
    // Saat menunggu penerbangan, geserannya cukup dari lapisan di bawah.
    val base = this.enterReveal(delayMs, if (afterFlight) 0.dp else rise)
    if (!afterFlight) return base
    // Halaman yang sedang ditinggalkan tidak ikut disembunyikan oleh
    // penerbangan menuju halaman berikutnya.
    val leaving = LocalCoverVisibilityScope.current?.transition?.targetState == EnterExitState.PostExit
    val flying = !leaving && coverFlightPending()
    val landed by animateFloatAsState(
        targetValue = if (flying) 0f else 1f,
        animationSpec = if (flying) {
            snap()
        } else {
            tween(durationMillis = 360, delayMillis = delayMs / 3, easing = FastOutSlowInEasing)
        },
        label = "flightReveal",
    )
    return base.graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = landed
        translationY = (1f - landed) * rise.toPx()
    }
}

/**
 * Memunculkan bagian halaman yang baru ada SETELAH halaman selesai terbuka,
 * mis. baris meta dan tombol aksi Liked Music yang menunggu daftar lagu dari
 * jaringan. [enterReveal] sudah bernilai penuh begitu transisi masuk selesai,
 * jadi bagian seperti itu muncul mendadak. Bagian yang sudah ada saat halaman
 * terbuka tidak disentuh (itu urusan [flightReveal]); halaman yang didatangi
 * kembali tampil utuh.
 */
@Composable
internal fun Modifier.lateReveal(delayMs: Int = 0, rise: Dp = 12.dp): Modifier {
    val visibility = LocalCoverVisibilityScope.current ?: return this
    if (LocalPageRevisit.current) return this
    val transition = visibility.transition
    // Dicatat sekali per item: scroll keluar-masuk tidak mengulang animasinya.
    var pending by rememberSaveable {
        mutableStateOf(
            transition.currentState == EnterExitState.Visible &&
                transition.targetState == EnterExitState.Visible,
        )
    }
    val progress = remember { Animatable(if (pending) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (pending) {
            delay(delayMs.toLong())
            progress.animateTo(1f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
            pending = false
        }
    }
    return this.graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = progress.value
        translationY = (1f - progress.value) * rise.toPx()
    }
}