package com.mewmix.glaive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.mewmix.glaive.data.GlaiveItem
import kotlin.math.sqrt

@Composable
fun DiskUsageMap(items: List<GlaiveItem>) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalSize = items.sumOf { it.size }.toFloat()
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            var currentOffset = Offset.Zero
            for (item in items) {
                val itemRatio = item.size.toFloat() / totalSize
                val itemArea = width * height * itemRatio
                val radius = sqrt(itemArea / kotlin.math.PI.toFloat())

                drawCircle(
                    color = getTypeColor(item.type),
                    radius = radius,
                    center = currentOffset + Offset(radius, radius)
                )

                currentOffset = currentOffset.copy(x = currentOffset.x + radius * 2)
                if (currentOffset.x + radius * 2 > width) {
                    currentOffset = currentOffset.copy(x = 0f, y = currentOffset.y + radius * 2)
                }
            }
        }
    }
}
