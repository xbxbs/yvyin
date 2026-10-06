package com.example.xuebimc

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class PlayerIconType {
    Play, Pause, Previous, Next, Star, More, MoreVertical, Lyrics, VolumeLow, VolumeHigh, AirPlay, Queue, Lossless, Share, Info, Download, Album, Artist, AddToPlaylist, Settings
}

@Composable
fun PlayerIcon(
    icon: PlayerIconType,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    filled: Boolean = false
) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val outline = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (icon) {
                PlayerIconType.Settings -> {
                    val gear = Path()
                    repeat(48) { step ->
                        val angle = -PI / 2 + step * PI / 24
                        val radius = if (step % 6 in 1..3) 9.5f else 7.5f
                        val x = 12f + cos(angle).toFloat() * radius
                        val y = 12f + sin(angle).toFloat() * radius
                        if (step == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                    }
                    gear.close()
                    drawPath(gear, tint, style = outline)
                    drawCircle(tint, 3f, Offset(12f, 12f), style = outline)
                }
                PlayerIconType.AddToPlaylist -> {
                    drawCircle(tint, 9f, Offset(12f, 12f), style = outline)
                    drawLine(tint, Offset(7.5f, 12f), Offset(16.5f, 12f), 1.6f, StrokeCap.Round)
                    drawLine(tint, Offset(12f, 7.5f), Offset(12f, 16.5f), 1.6f, StrokeCap.Round)
                }
                PlayerIconType.Download -> {
                    drawLine(tint, Offset(12f, 3f), Offset(12f, 15f), 1.6f, StrokeCap.Round)
                    drawPath(Path().apply { moveTo(8f, 11f); lineTo(12f, 15f); lineTo(16f, 11f) }, tint, style = outline)
                    drawPath(Path().apply { moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f) }, tint, style = outline)
                }
                PlayerIconType.Album -> {
                    drawRoundRect(tint, Offset(3.5f, 3.5f), Size(17f, 17f), CornerRadius(2.5f), style = outline)
                    drawCircle(tint, 5f, Offset(12f, 12f), style = outline)
                    drawCircle(tint, 1f, Offset(12f, 12f))
                }
                PlayerIconType.Artist -> {
                    drawCircle(tint, 4f, Offset(12f, 7f), style = outline)
                    drawPath(Path().apply { moveTo(4f, 21f); cubicTo(4f, 12f, 20f, 12f, 20f, 21f) }, tint, style = outline)
                }
                PlayerIconType.Share -> {
                    val box = Path().apply {
                        moveTo(7f, 9f); lineTo(4.5f, 9f); lineTo(4.5f, 20f)
                        lineTo(19.5f, 20f); lineTo(19.5f, 9f); lineTo(17f, 9f)
                    }
                    drawPath(box, tint, style = outline)
                    drawLine(tint, Offset(12f, 14f), Offset(12f, 2.5f), 1.6f, StrokeCap.Round)
                    drawPath(Path().apply { moveTo(8.5f, 6f); lineTo(12f, 2.5f); lineTo(15.5f, 6f) }, tint, style = outline)
                }
                PlayerIconType.Info -> {
                    drawCircle(tint, 9f, Offset(12f, 12f), style = outline)
                    drawCircle(tint, 1f, Offset(12f, 7.5f))
                    drawLine(tint, Offset(12f, 11f), Offset(12f, 17f), 1.6f, StrokeCap.Round)
                }
                PlayerIconType.Play -> {
                    val shape = Path().apply {
                        moveTo(6f, 3.2f)
                        cubicTo(6f, 2.1f, 6.8f, 1.8f, 7.8f, 2.4f)
                        lineTo(21f, 10.7f)
                        cubicTo(22f, 11.3f, 22f, 12.7f, 21f, 13.3f)
                        lineTo(7.8f, 21.6f)
                        cubicTo(6.8f, 22.2f, 6f, 21.9f, 6f, 20.8f)
                        close()
                    }
                    drawPath(shape, tint)
                }
                PlayerIconType.Pause -> {
                    drawRoundRect(tint, Offset(3.5f, 1f), Size(6.5f, 22f), CornerRadius(1.4f))
                    drawRoundRect(tint, Offset(13.5f, 1f), Size(6.5f, 22f), CornerRadius(1.4f))
                }
                PlayerIconType.Previous, PlayerIconType.Next -> {
                    scale(
                        scaleX = if (icon == PlayerIconType.Next) 1f else -1f,
                        scaleY = 1f,
                        pivot = Offset(12f, 12f)
                    ) {
                        for (index in 0..1) {
                            val start = .6f + index * 11.4f
                            val shape = Path().apply {
                                moveTo(start + .8f, 5.6f)
                                quadraticTo(start, 5.6f, start, 6.4f)
                                lineTo(start, 17.6f)
                                quadraticTo(start, 18.4f, start + .8f, 18.4f)
                                quadraticTo(start + 1.05f, 18.4f, start + 1.45f, 18.15f)
                                lineTo(start + 10.9f, 12.7f)
                                quadraticTo(start + 11.9f, 12f, start + 10.9f, 11.3f)
                                lineTo(start + 1.45f, 5.85f)
                                quadraticTo(start + 1.05f, 5.6f, start + .8f, 5.6f)
                                close()
                            }
                            drawPath(shape, tint)
                        }
                    }
                }
                PlayerIconType.Star -> {
                    val shape = Path().apply {
                        for (index in 0 until 10) {
                            val angle = -PI / 2 + index * PI / 5
                            val radius = if (index % 2 == 0) 9.4f else 4.3f
                            val pointX = 12f + cos(angle).toFloat() * radius
                            val pointY = 12f + sin(angle).toFloat() * radius
                            if (index == 0) moveTo(pointX, pointY) else lineTo(pointX, pointY)
                        }
                        close()
                    }
                    if (filled) drawPath(shape, tint) else drawPath(
                        shape, tint,
                        style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                PlayerIconType.More -> {
                    for (center in listOf(5f, 12f, 19f)) drawCircle(tint, 2.05f, Offset(center, 12f))
                }
                PlayerIconType.MoreVertical -> {
                    for (center in listOf(5f, 12f, 19f)) drawCircle(tint, 2.05f, Offset(12f, center))
                }
                PlayerIconType.Lyrics -> {
                    val bubble = Path().apply {
                        moveTo(6f, 3f)
                        lineTo(18f, 3f)
                        quadraticTo(22f, 3f, 22f, 7f)
                        lineTo(22f, 15f)
                        quadraticTo(22f, 19f, 18f, 19f)
                        lineTo(11f, 19f)
                        lineTo(7.2f, 22.2f)
                        quadraticTo(6.5f, 22.8f, 6.5f, 21.8f)
                        lineTo(6.5f, 19f)
                        quadraticTo(2f, 18.8f, 2f, 15f)
                        lineTo(2f, 7f)
                        quadraticTo(2f, 3f, 6f, 3f)
                        close()
                    }
                    drawPath(bubble, tint, style = outline)
                    for (start in listOf(7f, 13.2f)) {
                        drawRoundRect(tint, Offset(start, 8f), Size(3.8f, 3.8f), CornerRadius(1.9f))
                        val quote = Path().apply {
                            moveTo(start + 2.8f, 11f)
                            quadraticTo(start + 3.4f, 13.3f, start + .5f, 14.2f)
                        }
                        drawPath(quote, tint, style = Stroke(1.5f, cap = StrokeCap.Round))
                    }
                }
                PlayerIconType.VolumeLow, PlayerIconType.VolumeHigh -> {
                    val speaker = Path().apply {
                        moveTo(3.5f, 9f)
                        lineTo(6.5f, 9f)
                        lineTo(10.7f, 5.8f)
                        quadraticTo(12f, 4.8f, 12f, 6.5f)
                        lineTo(12f, 17.5f)
                        quadraticTo(12f, 19.2f, 10.7f, 18.2f)
                        lineTo(6.5f, 15f)
                        lineTo(3.5f, 15f)
                        quadraticTo(2f, 15f, 2f, 13.5f)
                        lineTo(2f, 10.5f)
                        quadraticTo(2f, 9f, 3.5f, 9f)
                        close()
                    }
                    translate(left = if (icon == PlayerIconType.VolumeLow) 5f else 0f) {
                        drawPath(speaker, tint)
                    }
                    if (icon == PlayerIconType.VolumeHigh) {
                        for (index in 0..2) {
                            val radius = 4f + index * 3.4f
                            drawArc(
                                tint, -48f, 96f, false,
                                Offset(10f - radius, 12f - radius), Size(radius * 2, radius * 2),
                                style = Stroke(1.4f, cap = StrokeCap.Round)
                            )
                        }
                    }
                }
                PlayerIconType.AirPlay -> {
                    for (radius in listOf(4.4f, 7.4f, 10.4f)) {
                        drawArc(
                            tint, 136f, 268f, false,
                            Offset(12f - radius, 11f - radius), Size(radius * 2, radius * 2),
                            style = Stroke(1.5f, cap = StrokeCap.Round)
                        )
                    }
                    val triangle = Path().apply {
                        moveTo(11.6f, 14.2f)
                        quadraticTo(12f, 13.7f, 12.4f, 14.2f)
                        lineTo(19.1f, 22f)
                        quadraticTo(19.6f, 22.6f, 18.8f, 22.6f)
                        lineTo(5.2f, 22.6f)
                        quadraticTo(4.4f, 22.6f, 4.9f, 22f)
                        close()
                    }
                    drawPath(triangle, tint)
                }
                PlayerIconType.Queue -> {
                    for (centerY in listOf(5f, 12f, 19f)) {
                        drawCircle(tint, 1.4f, Offset(3f, centerY))
                        drawLine(tint, Offset(8f, centerY), Offset(22f, centerY), 1.6f, StrokeCap.Round)
                    }
                }
                PlayerIconType.Lossless -> {
                    for (index in 0..3) {
                        val wave = Path().apply {
                            val start = 1f + index * 2.9f
                            moveTo(start, 12f)
                            cubicTo(start + 1.5f, 1f, start + 4f, 1f, start + 6.5f, 12f)
                            cubicTo(start + 9f, 23f, start + 11.5f, 23f, start + 13f, 12f)
                        }
                        drawPath(wave, tint, style = Stroke(.75f, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}
