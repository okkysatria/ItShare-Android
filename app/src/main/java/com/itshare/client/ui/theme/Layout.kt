package com.itshare.client.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared screen gutters that adapt to compact phones, regular phones, and tablets. */
@Composable
fun itShareScreenGutter(): Dp {
    val containerWidth = LocalWindowInfo.current.containerSize.width
    val width = with(LocalDensity.current) { containerWidth.toDp().value }
    return when {
        width < 360 -> 12.dp
        width >= 600 -> 24.dp
        else -> 20.dp
    }
}
