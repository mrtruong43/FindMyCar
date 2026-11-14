package com.truong.findmycar.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.truong.findmycar.ui.theme.MintAccent
import com.truong.findmycar.ui.theme.OutlineSubtle
import com.truong.findmycar.ui.theme.TextPrimary
import com.truong.findmycar.ui.theme.WarningAmber
import com.truong.findmycar.util.GeoMath
import kotlin.math.abs

@Composable
fun ArrowCompass(
    relativeArrowDeg: Float,
    isVeryClose: Boolean,
    isLowAccuracy: Boolean,
    modifier: Modifier = Modifier
) {
    // Track continuous unwrapped angle so Compose spring always rotates via shortest arc.
    var continuousTargetAngle by remember { mutableFloatStateOf(relativeArrowDeg) }
    LaunchedEffect(relativeArrowDeg) {
        val delta = GeoMath.shortestAngleDeltaDegrees(
            GeoMath.normalizeAngleDegrees(continuousTargetAngle),
            GeoMath.normalizeAngleDegrees(relativeArrowDeg)
        )
        continuousTargetAngle += delta
    }

    val animatedAngle by animateFloatAsState(
        targetValue = continuousTargetAngle,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "arrow_rotation"
    )

    val isAligned = !isLowAccuracy && !isVeryClose && abs(relativeArrowDeg) < 15f

    val infiniteTransition = rememberInfiniteTransition(label = "proximity_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.minDimension / 2f
            val dashEffect = if (isLowAccuracy) {
                PathEffect.dashPathEffect(floatArrayOf(14f, 12f), 0f)
            } else {
                null
            }

            // Concentric rings
            val ringRadii = listOf(maxRadius * 0.92f, maxRadius * 0.66f, maxRadius * 0.40f)
            ringRadii.forEachIndexed { idx, radius ->
                val isOuter = idx == 0
                val ringColor = when {
                    isVeryClose -> MintAccent.copy(alpha = 0.35f)
                    isLowAccuracy -> WarningAmber.copy(alpha = 0.30f)
                    isAligned && isOuter -> MintAccent.copy(alpha = 0.85f)
                    else -> OutlineSubtle.copy(alpha = 0.7f)
                }
                drawCircle(
                    color = ringColor,
                    radius = radius,
                    style = Stroke(
                        width = if (isOuter && isAligned) 2.5.dp.toPx() else 1.2.dp.toPx(),
                        pathEffect = dashEffect
                    )
                )
            }

            if (isVeryClose) {
                // Breathing glowing dot for < 8m
                val baseDotRadius = maxRadius * 0.15f
                drawCircle(
                    color = MintAccent.copy(alpha = 0.22f),
                    radius = baseDotRadius * 1.9f * pulseScale
                )
                drawCircle(
                    color = MintAccent,
                    radius = baseDotRadius * pulseScale
                )
            } else if (isLowAccuracy) {
                // Wide translucent sector/cone when compass needs figure-8 calibration
                rotate(degrees = animatedAngle, pivot = center) {
                    val sectorRadius = maxRadius * 0.72f
                    drawArc(
                        color = WarningAmber.copy(alpha = 0.28f),
                        startAngle = -130f,
                        sweepAngle = 80f,
                        useCenter = true,
                        topLeft = Offset(center.x - sectorRadius, center.y - sectorRadius),
                        size = Size(sectorRadius * 2f, sectorRadius * 2f)
                    )
                    drawArc(
                        color = WarningAmber,
                        startAngle = -130f,
                        sweepAngle = 80f,
                        useCenter = true,
                        topLeft = Offset(center.x - sectorRadius, center.y - sectorRadius),
                        size = Size(sectorRadius * 2f, sectorRadius * 2f),
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                }
            } else {
                // Thin-stroke precision directional arrow (pointing up at 0°)
                val arrowColor = if (isAligned) MintAccent else TextPrimary
                rotate(degrees = animatedAngle, pivot = center) {
                    val tipY = center.y - maxRadius * 0.62f
                    val tailY = center.y + maxRadius * 0.44f
                    val wingY = center.y - maxRadius * 0.22f
                    val wingHalfW = maxRadius * 0.26f

                    val arrowPath = Path().apply {
                        moveTo(center.x, tailY)
                        lineTo(center.x, tipY)
                        moveTo(center.x - wingHalfW, wingY)
                        lineTo(center.x, tipY)
                        lineTo(center.x + wingHalfW, wingY)
                    }

                    drawPath(
                        path = arrowPath,
                        color = arrowColor,
                        style = Stroke(
                            width = 3.2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
    }
}
