package com.gita.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// Black + silver palette (no white anywhere)
val Black = Color(0xFF0A0A0A)
val Panel = Color(0xFF141414)
val Silver = Color(0xFFB3B3AF)
val SilverDim = Color(0xFF767672)
val SilverDark = Color(0xFF45453F)

// Brushed-metal gradients
val MetalBrush = Brush.verticalGradient(
    0f to Color(0xFFC2C2BE),
    0.48f to Color(0xFF9A9A96),
    0.52f to Color(0xFF858581),
    1f to Color(0xFFA8A8A4)
)

val ChromeBrush = Brush.verticalGradient(
    0f to Color(0xFFC4C4C0),
    0.5f to Color(0xFF8E8E8A),
    0.52f to Color(0xFF787874),
    1f to Color(0xFFA6A6A2)
)

val EdgeBrush = Brush.verticalGradient(
    listOf(Color(0xFFA0A09C), Color(0xFF30302D))
)

val BackgroundBrush = Brush.verticalGradient(
    listOf(Color(0xFF202020), Color(0xFF080808))
)

// Metal surface: gradient + fine brushed lines + bevelled edge
fun Modifier.metal(shape: Shape): Modifier = this
    .clip(shape)
    .background(MetalBrush)
    .drawBehind {
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = 0.07f), Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
    }
    .border(1.dp, EdgeBrush, shape)

@Composable
fun GitaBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(BackgroundBrush), content = content)
}

@Composable
fun ChromeText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(
            brush = ChromeBrush,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textAlign = textAlign
        )
    )
}

enum class Glyph { Prev, Next, Play, Pause }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
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
fun MetalButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 76.dp,
    height: Dp = 52.dp
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width, height)
            .metal(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        GlyphIcon(glyph, Color(0xFF1A1A1A), Modifier.size(height * 0.42f))
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
                .metal(CircleShape)
                .clickable(onClick = onClick)
        ) {
            GlyphIcon(glyph, Color(0xFF1A1A1A), Modifier.size(diameter * 0.34f))
        }
    }
}

// Left = off, right = on
@Composable
fun MetalToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val knobOffset by animateDpAsState(if (checked) 28.dp else 0.dp, label = "knob")
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(width = 60.dp, height = 32.dp)
            .clip(shape)
            .background(if (checked) Color(0xFF3A3A36) else Color(0xFF0C0C0C))
            .border(1.dp, SilverDark, shape)
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(26.dp)
                .metal(CircleShape)
        )
    }
}

private fun heartPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.5f, h * 0.92f)
    cubicTo(w * 0.18f, h * 0.66f, w * 0.04f, h * 0.48f, w * 0.04f, h * 0.30f)
    cubicTo(w * 0.04f, h * 0.14f, w * 0.17f, h * 0.06f, w * 0.29f, h * 0.06f)
    cubicTo(w * 0.40f, h * 0.06f, w * 0.47f, h * 0.12f, w * 0.50f, h * 0.20f)
    cubicTo(w * 0.53f, h * 0.12f, w * 0.60f, h * 0.06f, w * 0.71f, h * 0.06f)
    cubicTo(w * 0.83f, h * 0.06f, w * 0.96f, h * 0.14f, w * 0.96f, h * 0.30f)
    cubicTo(w * 0.96f, h * 0.48f, w * 0.82f, h * 0.66f, w * 0.50f, h * 0.92f)
    close()
}

@Composable
fun HeartIcon(filled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick)
    ) {
        Canvas(Modifier.size(22.dp)) {
            val path = heartPath(size.width, size.height)
            if (filled) {
                drawPath(path, brush = MetalBrush)
                drawPath(path, color = Color(0xFF2A2A28), style = Stroke(1.dp.toPx()))
            } else {
                drawPath(path, color = SilverDim, style = Stroke(1.6.dp.toPx()))
            }
        }
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
            drawCircle(SilverDim, r, c, style = Stroke(2.dp.toPx()))
            drawCircle(Color(0xFF9E9E9A), r * 0.3f, c)
            drawCircle(Color(0xFF3A3A38), r * 0.3f, c, style = Stroke(1.5.dp.toPx()))
            drawCircle(Color(0xFF1A1A1A), r * 0.05f, Offset(c.x, c.y - r * 0.18f))
            drawCircle(Color(0xFF0A0A0A), r * 0.035f, c)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        Silver.copy(alpha = 0.10f),
                        Color.Transparent,
                        Color.Transparent,
                        Silver.copy(alpha = 0.10f),
                        Color.Transparent
                    )
                ),
                radius = size.minDimension / 2,
                center = center
            )
        }
    }
}