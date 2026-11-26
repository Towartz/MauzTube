package com.mauztube.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Dimensioni REALI della finestra in dp (rotazione, multi-finestra, pieghevoli).
 * Misurate dal contenitore radice ([ProvideWindowSize]) invece di `LocalConfiguration` o
 * `LocalWindowInfo.containerSize`, che con `configChanges` e `applyOverrideConfiguration`
 * possono restare bloccati all'orientamento precedente dopo l'uscita dallo schermo intero.
 */
@Immutable
data class WindowSize(
    val widthDp: Float,
    val heightDp: Float,
    val fontScale: Float,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
) {
    /** Orizzontale su telefono, pieghevoli aperti, tablet. */
    val wide: Boolean get() = widthDp >= 600f
    val landscape: Boolean get() = widthDp > heightDp
}

val LocalWindowSize = compositionLocalOf<WindowSize?> { null }

@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val size = WindowSize(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            fontScale = density.fontScale,
            widthPx = constraints.maxWidth,
            heightPx = constraints.maxHeight,
        )
        CompositionLocalProvider(LocalWindowSize provides size, content = content)
    }
}

@Composable
fun windowSize(): WindowSize {
    LocalWindowSize.current?.let { return it }
    val px = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return WindowSize(
        widthDp = px.width / density.density,
        heightDp = px.height / density.density,
        fontScale = density.fontScale,
        widthPx = px.width,
        heightPx = px.height,
    )
}
