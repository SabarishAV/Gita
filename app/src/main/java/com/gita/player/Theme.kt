package com.gita.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Black + silver palette
val Black = Color(0xFF0A0A0A)
val Panel = Color(0xFF151515)
val Silver = Color(0xFFD6D6D2)
val SilverDim = Color(0xFF8E8E8A)
val SilverDark = Color(0xFF55554F)

val MetalBrush = Brush.verticalGradient(
    0f to Color(0xFFF2F2EE),
    0.48f to Color(0xFFBEBEBA),
    0.52f to Color(0xFFA6A6A2),
    1f to Color(0xFFD2D2CE)
)

val BackgroundBrush = Brush.verticalGradient(
    listOf(Color(0xFF212121), Color(0xFF080808))
)

@Composable
fun GitaBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(BackgroundBrush), content = content)
}

enum class Glyph { Prev, Next, Play, Pause }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (glyph) {
            Glyph.Play -> drawPath(
                Path().apply {
                    moveTo(w * 0.25f, h * 0.15f)
                    lineTo(w * 0.85f, h * 0.5f)
                    lineTo(w * 0.25f, h * 0.85f)
                    close()
                }, color
            )
            Glyph.Pause -> {
                drawRect(color, Offset(w * 0.2f, h * 0.15f), Size(w * 0.22f, h * 0.7f))
                drawRect(color, Offset(w * 0.58f, h * 0.15f), Size(w * 0.22f, h * 0.7f))
            }
            Glyph.Next -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.15f, h * 0.2f)
                        lineTo(w * 0.68f, h * 0.5f)
                        lineTo(w * 0.15f, h * 0.8f)
                        close()
                    }, color
                )
                drawRect(color, Offset(w * 0.75f, h * 0.2f), Size(w * 0.11f, h * 0.6f))
            }
            Glyph.Prev -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.85f, h * 0.2f)
                        lineTo(w * 0.32f, h * 0.5f)
                        lineTo(w * 0.85f, h * 0.8f)
                        close()
                    }, color
                )
                drawRect(color, Offset(w * 0.14f, h * 0.2f), Size(w * 0.11f, h * 0.6f))
            }
        }
    }
}

@Composable
fun MetalButton(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width = 76.dp, height = 52.dp)
            .clip(shape)
            .background(MetalBrush)
            .border(1.dp, SilverDark, shape)
            .clickable(onClick = onClick)
    ) {
        GlyphIcon(glyph, Color(0xFF1A1A1A), Modifier.size(22.dp))
    }
}

@Composable
fun MetalRoundButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 84.dp
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Color(0xFF050505))
            .border(2.dp, SilverDark, CircleShape)
            .padding(diameter * 0.08f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MetalBrush)
                .clickable(onClick = onClick)
        ) {
            GlyphIcon(glyph, Color(0xFF1A1A1A), Modifier.size(diameter * 0.34f))
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
                tint = Silver,
                modifier = Modifier.size(26.dp)
            )
        }
        Icon(
            Icons.Filled.FavoriteBorder,
            contentDescription = "Favorite",
            tint = Silver,
            modifier = Modifier.size(26.dp)
        )
    }
}

// Shown in place of album art when a song has no cover image
@Composable
fun VinylFallback(playing: Boolean, modifier: Modifier = Modifier) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(4000, easing = LinearEasing))
            }
        }
    }
    Box(modifier.fillMaxSize().padding(14.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value }
        ) {
            val c = center
            val r = size.minDimension / 2
            drawCircle(Color(0xFF0C0C0C), r, c)
            var i = 0
            var rad = r * 0.96f
            while (rad > r * 0.42f) {
                drawCircle(
                    if (i % 2 == 0) Color(0xFF2A2A2A) else Color(0xFF181818),
                    rad, c, style = Stroke(1.dp.toPx())
                )
                rad -= r * 0.035f
                i++
            }
            drawCircle(Silver.copy(alpha = 0.7f), r, c, style = Stroke(2.dp.toPx()))
            drawCircle(Color(0xFFBDBDB9), r * 0.3f, c)
            drawCircle(Color(0xFF3A3A38), r * 0.3f, c, style = Stroke(1.5.dp.toPx()))
            drawCircle(Color(0xFF1A1A1A), r * 0.05f, Offset(c.x, c.y - r * 0.18f))
            drawCircle(Color(0xFF0A0A0A), r * 0.035f, c)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent
                    )
                ),
                radius = size.minDimension / 2,
                center = center
            )
        }
    }
}