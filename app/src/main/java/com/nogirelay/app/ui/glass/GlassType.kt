package com.nogirelay.app.ui.glass

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.nogirelay.app.ui.withFixes

/**
 * The type scale. Every text in the app picks one of these steps instead of
 * an ad-hoc size; weight and color are still set per use. Line heights are
 * part of the step so stacked text keeps an even rhythm.
 *
 * | Step        | Size / line | Typical use                                  |
 * |-------------|-------------|----------------------------------------------|
 * | LargeTitle  | 32 / 38     | Page headers                                 |
 * | Title1      | 22 / 28     | Detail page headers                          |
 * | Title2      | 20 / 26     | Sheet and dialog titles                      |
 * | Title3      | 17 / 24     | Card headlines, article titles               |
 * | Headline    | 15 / 21     | Names, section and settings group titles     |
 * | Body        | 15 / 22     | Message text                                 |
 * | Article     | 15 / 24     | Long-form blog paragraphs                    |
 * | Callout     | 14 / 20     | Fields, list rows, translations              |
 * | Subhead     | 13 / 18     | Previews, button labels, secondary rows      |
 * | Footnote    | 12 / 16     | Descriptions, metadata, status lines         |
 * | Caption     | 11 / 14     | Timestamps, field labels                     |
 * | Caption2    | 10 / 12     | Navigation labels, tags                      |
 * | Badge       |  9 / 11     | Counters inside badges                       |
 * | Micro       |  8 / 10     | Compact badges and tags                      |
 */
object GlassType {
    val LargeTitle = step(32.sp, 38.sp, FontWeight.Bold, letterSpacing = (-0.5).sp)
    val Title1 = step(22.sp, 28.sp, FontWeight.Bold)
    val Title2 = step(20.sp, 26.sp, FontWeight.Bold)
    val Title3 = step(17.sp, 24.sp, FontWeight.SemiBold)
    val Headline = step(15.sp, 21.sp, FontWeight.SemiBold)
    val Body = step(15.sp, 22.sp)
    val Article = step(15.sp, 24.sp)
    val Callout = step(14.sp, 20.sp)
    val Subhead = step(13.sp, 18.sp)
    val Footnote = step(12.sp, 16.sp)
    val Caption = step(11.sp, 14.sp)
    val Caption2 = step(10.sp, 12.sp, FontWeight.Medium)
    val Badge = step(9.sp, 11.sp, FontWeight.Bold)
    val Micro = step(8.sp, 10.sp, FontWeight.Medium)

    private fun step(
        size: TextUnit,
        lineHeight: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        letterSpacing: TextUnit = TextUnit.Unspecified,
    ): TextStyle = TextStyle(
        fontSize = size,
        lineHeight = lineHeight,
        fontWeight = weight,
        letterSpacing = letterSpacing,
    ).withFixes()
}
