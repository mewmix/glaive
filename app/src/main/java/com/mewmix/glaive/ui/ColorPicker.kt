package com.mewmix.glaive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SweepGradient
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun HsvColorPicker(
    initialColor: Color,
    onColorChanged: (Color) -> Unit
) {
    val initialHsv = remember(initialColor) {
        val hsv = floatArrayOf(0f, 0f, 0f)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), hsv)
        hsv
    }

    var hue by remember { mutableStateOf(initialHsv[0]) }
    var saturation by remember { mutableStateOf(initialHsv[1]) }
    var value by remember { mutableStateOf(initialHsv[2]) }

    val currentColor = remember(hue, saturation, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    }

    LaunchedEffect(currentColor) {
        onColorChanged(currentColor)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(16.dp)
    ) {
        // Saturation/Value Box
        SatValPanel(
            hue = hue,
            saturation = saturation,
            value = value,
            onSatValChanged = { s, v ->
                saturation = s
                value = v
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Hue Wheel
        HueWheel(
            hue = hue,
            onHueChanged = { hue = it }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Hex Display
        Text(
            text = "#${Integer.toHexString(currentColor.toArgb()).uppercase().takeLast(6)}",
            color = Color.White,
            fontSize = 18.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

@Composable
fun SatValPanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onSatValChanged: (Float, Float) -> Unit
) {
    val density = LocalDensity.current.density

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.Gray, RoundedCornerShape(12.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val s = (offset.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        onSatValChanged(s, v)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        onSatValChanged(s, v)
                    }
                }
        ) {
            // Horizontal: White -> Hue
            val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, hueColor)
                )
            )
            // Vertical: Transparent -> Black
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black)
                )
            )
        }

        // Selection Indicator
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val offsetX = maxWidth * saturation
            val offsetY = maxHeight * (1 - value)

            val offsetXPx = with(LocalDensity.current) { offsetX.toPx() }
            val offsetYPx = with(LocalDensity.current) { offsetY.toPx() }
            val offsetAdjustment = with(LocalDensity.current) { 10.dp.toPx() }

            Box(
                modifier = Modifier
                    .offset { IntOffset((offsetXPx - offsetAdjustment).roundToInt(), (offsetYPx - offsetAdjustment).roundToInt()) }
                    .size(20.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .border(1.dp, Color.Black, CircleShape) // Inner contrast
            )
        }
    }
}

@Composable
fun HueWheel(
    hue: Float,
    onHueChanged: (Float) -> Unit
) {
    val wheelSize = 200.dp

    Box(
        modifier = Modifier.size(wheelSize),
        contentAlignment = Alignment.Center
    ) {
        // The Wheel Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        handleHueInput(offset, size.width.toFloat(), size.height.toFloat(), onHueChanged)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        handleHueInput(change.position, size.width.toFloat(), size.height.toFloat(), onHueChanged)
                    }
                }
        ) {
            // Draw Sweep Gradient
            val center = Offset(size.width / 2, size.height / 2)
            val radius = min(size.width, size.height) / 2

            // Standard Hue Rainbow
            val colors = listOf(
                Color.Red,
                Color.Yellow,
                Color.Green,
                Color.Cyan,
                Color.Blue,
                Color.Magenta,
                Color.Red
            )

            val strokeWidth = 40f

            drawCircle(
                brush = Brush.sweepGradient(colors, center),
                radius = radius,
                style = Stroke(width = strokeWidth)
            )

            // Hue Indicator drawn within the Canvas so it stays aligned
            val angleRad = Math.toRadians(hue.toDouble())
            val indicatorCenter = Offset(
                x = center.x + (radius * cos(angleRad).toFloat()),
                y = center.y + (radius * sin(angleRad).toFloat())
            )
            // Outer white ring for visibility
            drawCircle(color = Color.White, radius = 8f, center = indicatorCenter)
            // Inner dark dot for contrast
            drawCircle(color = Color.Black, radius = 5f, center = indicatorCenter)
        }
    }
}

private fun handleHueInput(offset: Offset, width: Float, height: Float, onHueChanged: (Float) -> Unit) {
    val centerX = width / 2
    val centerY = height / 2
    val dx = offset.x - centerX
    val dy = offset.y - centerY
    val angleRad = atan2(dy, dx)
    var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
    if (angleDeg < 0) angleDeg += 360f
    onHueChanged(angleDeg)
}
