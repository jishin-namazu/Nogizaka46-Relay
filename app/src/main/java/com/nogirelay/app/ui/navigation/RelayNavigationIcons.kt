package com.nogirelay.app.ui.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal object RelayNavigationIcons {
    private const val homeContour = "M 4 10 L 10.6 4.5 Q 12 3.3 13.4 4.5 L 20 10 Q 21 10.8 21 12 L 21 18.5 Q 21 21 18.5 21 L 5.5 21 Q 3 21 3 18.5 L 3 12 Q 3 10.8 4 10 Z"
    private const val homeDoor = "M 9 21 L 9 15.5 Q 9 14 10.5 14 L 13.5 14 Q 15 14 15 15.5 L 15 21"
    private const val inboxContour = "M 6 4 L 18 4 Q 21 4 21 7 L 21 17 Q 21 20 18 20 L 6 20 Q 3 20 3 17 L 3 7 Q 3 4 6 4 Z"
    private const val inboxTray = "M 3 13 L 7.5 13 Q 8.5 13 9 14 L 9.5 15 Q 10 16 11 16 L 13 16 Q 14 16 14.5 15 L 15 14 Q 15.5 13 16.5 13 L 21 13"
    private const val inboxWindow = "M 7 6 L 17 6 Q 19 6 19 8 L 19 11 L 16.5 11 Q 14.3 11 13.2 13.2 L 12.8 14 L 11.2 14 L 10.8 13.2 Q 9.7 11 7.5 11 L 5 11 L 5 8 Q 5 6 7 6 Z"
    private const val blogContour = "M 7 3 L 17 3 Q 20 3 20 6 L 20 18 Q 20 21 17 21 L 7 21 Q 4 21 4 18 L 4 6 Q 4 3 7 3 Z"
    private const val blogLines = "M 8 8 L 16 8 M 8 12 L 16 12 M 8 16 L 13 16"
    private const val blogWindows = "M 8 7.2 L 16 7.2 A .8 .8 0 0 1 16 8.8 L 8 8.8 A .8 .8 0 0 1 8 7.2 Z M 8 11.2 L 16 11.2 A .8 .8 0 0 1 16 12.8 L 8 12.8 A .8 .8 0 0 1 8 11.2 Z M 8 15.2 L 13 15.2 A .8 .8 0 0 1 13 16.8 L 8 16.8 A .8 .8 0 0 1 8 15.2 Z"

    val homeOutline = outline("RelayHomeOutline", "$homeContour $homeDoor")
    val homeFilled = filled("RelayHomeFilled", "$homeContour $homeDoor Z")
    val inboxOutline = outline("RelayInboxOutline", "$inboxContour $inboxTray")
    val inboxFilled = filled("RelayInboxFilled", "$inboxContour $inboxWindow")
    val blogOutline = outline("RelayBlogOutline", "$blogContour $blogLines")
    val blogFilled = filled("RelayBlogFilled", "$blogContour $blogWindows")

    private fun outline(name: String, data: String) = vector(name, data, filled = false)
    private fun filled(name: String, data: String) = vector(name, data, filled = true)

    private fun vector(name: String, data: String, filled: Boolean): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = 1.65f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.EvenOdd,
            )
            .build()
}
