package com.tmuxer.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A compact, filled Shift symbol that stays legible next to direction icons. */
internal val ShiftFilledIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ShiftFilled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.04f, 2.76f)
            lineTo(2.73f, 11.07f)
            curveTo(2.16f, 11.64f, 2.56f, 12.62f, 3.37f, 12.62f)
            lineTo(7.12f, 12.62f)
            lineTo(7.12f, 20.2f)
            curveTo(7.12f, 21.19f, 7.93f, 22f, 8.92f, 22f)
            lineTo(15.08f, 22f)
            curveTo(16.07f, 22f, 16.88f, 21.19f, 16.88f, 20.2f)
            lineTo(16.88f, 12.62f)
            lineTo(20.63f, 12.62f)
            curveTo(21.44f, 12.62f, 21.84f, 11.64f, 21.27f, 11.07f)
            lineTo(12.96f, 2.76f)
            curveTo(12.43f, 2.23f, 11.57f, 2.23f, 11.04f, 2.76f)
            close()
        }
    }.build()
}
