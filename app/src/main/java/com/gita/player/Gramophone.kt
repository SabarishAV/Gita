package com.gita.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.unit.dp

@Composable
fun Gramophone(playing: Boolean, modifier: Modifier = Modifier) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(3000, easing = LinearEasing))
            }
        }
    }
    val armAngle by animateFloatAsState(
        targetValue = if (playing) 0f else -28f,
        animationSpec = tween(600),
        label = "tonearm"
    )

    Box(modifier.size(width = 200.dp, height = 150.dp)) {
        Canvas(
            Modifier
                .size(120.dp)
                .align(Alignment.CenterStart)
                .rotate(rotation.value)
        ) {
            val c = center
            val r = size.minDimension / 2
            drawCircle(Ink, r, c)
            listOf(0.92f, 0.82f, 0.72f, 0.62f, 0.52f).forEach {
                drawCircle(Color(0xFF3A3A3A), r * it, c, style = Stroke(1.dp.toPx()))
            }
            drawCircle(Paper, r * 0.33f, c)
            drawCircle(Ink, r * 0.33f, c, style = Stroke(2.dp.toPx()))
            drawCircle(Ink, r * 0.05f, Offset(c.x, c.y - r * 0.2f))
            drawCircle(Ink, r * 0.04f, c)
        }
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width * 0.9f, size.height * 0.1f)
            val end = Offset(size.width * 0.5f, size.height * 0.7f)
            rotateDraw(armAngle, pivot) {
                drawLine(Faded, pivot, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Ink, 7.dp.toPx(), pivot)
                drawCircle(Ink, 5.dp.toPx(), end)
            }
        }
    }
}

@Composable
fun HeartIcon(filled: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(8.dp)) {
        if (filled) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Icon(
            Icons.Filled.FavoriteBorder,
            contentDescription = "Favorite",
            tint = Ink,
            modifier = Modifier.size(26.dp)
        )
    }
}