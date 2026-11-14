package com.truong.findmycar.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.truong.findmycar.R
import com.truong.findmycar.data.VehicleHologramType
import com.truong.findmycar.ui.theme.MintAccent
import com.truong.findmycar.ui.theme.OutlineSubtle
import com.truong.findmycar.ui.theme.TextSecondary
import com.truong.findmycar.ui.theme.WarningAmber
import com.truong.findmycar.util.GeoMath
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class HologramStageMode {
    READY_TO_PARK,
    PARKED_LOCKED,
    GUIDING_TO_VEHICLE
}

private data class Vec3(val x: Float, val y: Float, val z: Float)

@Composable
fun ScooterHologramStage(
    mode: HologramStageMode,
    modifier: Modifier = Modifier,
    vehicleType: VehicleHologramType = VehicleHologramType.SCOOTER,
    tintColor: Color = MintAccent,
    relativeArrowDeg: Float = 0f,
    userHeadingDeg: Float = 0f,
    isVeryClose: Boolean = false,
    isLowAccuracy: Boolean = false,
    stepCount: Int = 0,
    isTracking: Boolean = false,
    enableTapSpin: Boolean = (mode == HologramStageMode.PARKED_LOCKED)
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Zero-Battery-Leak Lifecycle Guard: register rotation vector ONLY in RESUMED state.
    var tiltPitchDeg by remember { mutableFloatStateOf(0f) }
    var tiltRollDeg by remember { mutableFloatStateOf(0f) }
    DisposableEffect(lifecycleOwner, context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var basePitch: Float? = null
        var baseRoll: Float? = null
        var isRegistered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
                if (basePitch == null || baseRoll == null) {
                    basePitch = pitchDeg
                    baseRoll = rollDeg
                }
                val relPitch = (pitchDeg - (basePitch ?: pitchDeg)).coerceIn(-12f, 12f)
                val relRoll = (rollDeg - (baseRoll ?: rollDeg)).coerceIn(-12f, 12f)
                tiltPitchDeg += (relPitch - tiltPitchDeg) * 0.22f
                tiltRollDeg += (relRoll - tiltRollDeg) * 0.22f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun register() {
            if (!isRegistered && sensorManager != null && rotationSensor != null) {
                basePitch = null
                baseRoll = null
                sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                isRegistered = true
            }
        }

        fun unregister() {
            if (isRegistered && sensorManager != null) {
                sensorManager.unregisterListener(listener)
                isRegistered = false
            }
        }

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }

    val animatedTint by animateColorAsState(
        targetValue = when {
            isLowAccuracy && mode == HologramStageMode.GUIDING_TO_VEHICLE -> WarningAmber
            else -> tintColor
        },
        animationSpec = tween(durationMillis = 280),
        label = "hologram_tint"
    )

    // Stage 1: Hover & radar sweep (4s / 360°)
    val infiniteTransition = rememberInfiniteTransition(label = "hologram_idle")
    val hoverPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "hover_phase"
    )
    val radarSweepDeg by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_sweep"
    )
    // Stage 3 inward shockwave progress (1f -> 0f) when VERY_CLOSE
    val inwardShockwave by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inward_shockwave"
    )

    // Stage 2: Spring lock-down, ring tighten, and single vertical scanline sweep
    val lockOffsetDp = remember { Animatable(if (mode == HologramStageMode.PARKED_LOCKED) 14f else 0f) }
    val ringTightenScale = remember { Animatable(if (mode == HologramStageMode.PARKED_LOCKED) 1.18f else 1.0f) }
    val scanlineProgress = remember { Animatable(0f) }
    var showScanline by remember { mutableStateOf(false) }

    LaunchedEffect(mode) {
        if (mode == HologramStageMode.PARKED_LOCKED) {
            lockOffsetDp.snapTo(14f)
            ringTightenScale.snapTo(1.20f)
            scanlineProgress.snapTo(0f)
            showScanline = true
            launch {
                lockOffsetDp.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.56f,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            launch {
                ringTightenScale.animateTo(
                    targetValue = 0.94f,
                    animationSpec = spring(
                        dampingRatio = 0.65f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                scanlineProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing)
                )
                showScanline = false
            }
        } else {
            showScanline = false
            lockOffsetDp.snapTo(0f)
            ringTightenScale.snapTo(1.0f)
        }
    }

    // Stage 2: Outward ripple pulse synced with step increments during TrackingService
    val stepRipple = remember { Animatable(0f) }
    var lastStepCount by remember { mutableStateOf(stepCount) }
    LaunchedEffect(stepCount, isTracking, mode) {
        if (mode == HologramStageMode.PARKED_LOCKED && isTracking && stepCount > lastStepCount) {
            stepRipple.snapTo(0.01f)
            stepRipple.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = LinearOutSlowInEasing)
            )
            stepRipple.snapTo(0f)
        }
        lastStepCount = stepCount
    }

    // Stage 2: Tap 360° showroom spin
    val spinDeg = remember { Animatable(0f) }

    // Stage 3: Continuous unwrapped angles for 60fps shortest-arc rotation
    var cumulativeArrowDeg by remember { mutableFloatStateOf(relativeArrowDeg) }
    LaunchedEffect(relativeArrowDeg) {
        val delta = GeoMath.shortestAngleDeltaDegrees(
            GeoMath.normalizeAngleDegrees(cumulativeArrowDeg),
            GeoMath.normalizeAngleDegrees(relativeArrowDeg)
        )
        cumulativeArrowDeg += delta
    }
    val animatedArrowDeg by animateFloatAsState(
        targetValue = cumulativeArrowDeg,
        animationSpec = tween(durationMillis = 120, easing = LinearOutSlowInEasing),
        label = "hologram_arrow_yaw"
    )

    var cumulativeHeadingDeg by remember { mutableFloatStateOf(-userHeadingDeg) }
    LaunchedEffect(userHeadingDeg) {
        val target = -userHeadingDeg
        val delta = GeoMath.shortestAngleDeltaDegrees(
            GeoMath.normalizeAngleDegrees(cumulativeHeadingDeg),
            GeoMath.normalizeAngleDegrees(target)
        )
        cumulativeHeadingDeg += delta
    }
    val animatedCompassRingDeg by animateFloatAsState(
        targetValue = cumulativeHeadingDeg,
        animationSpec = tween(durationMillis = 120, easing = LinearOutSlowInEasing),
        label = "compass_ring_rot"
    )

    val radarZoom by animateFloatAsState(
        targetValue = if (mode == HologramStageMode.GUIDING_TO_VEHICLE && isVeryClose) 1.15f else 1.0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "radar_zoom"
    )

    val stageCd = when (mode) {
        HologramStageMode.READY_TO_PARK -> stringResource(R.string.hologram_stage_ready_cd)
        HologramStageMode.PARKED_LOCKED -> stringResource(R.string.hologram_stage_locked_cd)
        HologramStageMode.GUIDING_TO_VEHICLE -> ""
    }

    val interactionSource = remember { MutableInteractionSource() }
    val tapModifier = if (enableTapSpin) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null
        ) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scope.launch {
                val target = spinDeg.value + 360f
                spinDeg.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
                )
            }
        }
    } else {
        Modifier
    }

    val semanticModifier = if (stageCd.isNotEmpty()) {
        Modifier.semantics { contentDescription = stageCd }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(semanticModifier)
            .then(tapModifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = size.minDimension
            val perspectiveY = 0.48f
            val baseRadius = minDim * 0.43f * radarZoom *
                (if (mode == HologramStageMode.PARKED_LOCKED) ringTightenScale.value else 1f)

            val floorCenterY = when (mode) {
                HologramStageMode.GUIDING_TO_VEHICLE -> center.y + minDim * 0.04f
                else -> center.y + minDim * 0.14f
            }
            val floorCenter = Offset(center.x, floorCenterY)

            // 1. Perspective Radar Floor (concentric ellipses + radial lines + cardinal ticks)
            val compassRotationDeg = if (mode == HologramStageMode.GUIDING_TO_VEHICLE) {
                animatedCompassRingDeg
            } else {
                0f
            }
            drawPerspectiveRadarFloor(
                center = floorCenter,
                outerRadius = baseRadius,
                perspectiveY = perspectiveY,
                accentColor = animatedTint,
                compassRotationDeg = compassRotationDeg,
                isLowAccuracy = isLowAccuracy && mode == HologramStageMode.GUIDING_TO_VEHICLE,
                isVeryClose = isVeryClose && mode == HologramStageMode.GUIDING_TO_VEHICLE,
                isAligned = mode == HologramStageMode.GUIDING_TO_VEHICLE &&
                    !isLowAccuracy && !isVeryClose && abs(relativeArrowDeg) < 15f
            )

            // Stage 1: Soft rotating radar sweep sector (360° every 4s)
            if (mode == HologramStageMode.READY_TO_PARK) {
                drawSweepSector(
                    center = floorCenter,
                    outerRadius = baseRadius,
                    perspectiveY = perspectiveY,
                    sweepAngleDeg = radarSweepDeg,
                    color = animatedTint
                )
            }

            // Stage 2: Outward step ripple pulse while TrackingService is recording
            if (mode == HologramStageMode.PARKED_LOCKED && stepRipple.value > 0f) {
                val p = stepRipple.value
                val r = baseRadius * (0.28f + p * 0.85f)
                drawOval(
                    color = animatedTint.copy(alpha = (1f - p) * 0.65f),
                    topLeft = Offset(floorCenter.x - r, floorCenter.y - r * perspectiveY),
                    size = Size(r * 2f, r * 2f * perspectiveY),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Stage 3: Inward emerald shockwaves when VERY_CLOSE (< 10m)
            if (mode == HologramStageMode.GUIDING_TO_VEHICLE && isVeryClose) {
                listOf(0f, 0.5f).forEach { phaseOffset ->
                    val wave = (inwardShockwave + phaseOffset) % 1f
                    val r = baseRadius * (0.22f + wave * 0.86f)
                    val alpha = sin(wave * PI).toFloat().coerceIn(0f, 1f) * 0.65f
                    drawOval(
                        color = MintAccent.copy(alpha = alpha),
                        topLeft = Offset(floorCenter.x - r, floorCenter.y - r * perspectiveY),
                        size = Size(r * 2f, r * 2f * perspectiveY),
                        style = Stroke(width = 2.2.dp.toPx())
                    )
                }
            }

            // Compute vehicle yaw & vertical hover/lock offset
            val vehicleYawDeg = when (mode) {
                HologramStageMode.READY_TO_PARK -> 42f
                HologramStageMode.PARKED_LOCKED -> 42f + spinDeg.value
                HologramStageMode.GUIDING_TO_VEHICLE -> animatedArrowDeg
            }

            val hoverLiftPx = when (mode) {
                HologramStageMode.READY_TO_PARK -> (sin(hoverPhase) * 3f + 4f).dp.toPx()
                HologramStageMode.PARKED_LOCKED -> lockOffsetDp.value.dp.toPx()
                HologramStageMode.GUIDING_TO_VEHICLE -> 2.dp.toPx()
            }

            // Soft ambient under-glow shadow on radar floor
            val shadowRadiusX = baseRadius * 0.42f
            val shadowRadiusY = shadowRadiusX * perspectiveY * 0.72f
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedTint.copy(alpha = if (mode == HologramStageMode.READY_TO_PARK) 0.24f else 0.36f),
                        Color.Transparent
                    ),
                    center = floorCenter,
                    radius = shadowRadiusX
                ),
                topLeft = Offset(floorCenter.x - shadowRadiusX, floorCenter.y - shadowRadiusY),
                size = Size(shadowRadiusX * 2f, shadowRadiusY * 2f)
            )

            // Stage 3: Directional pointer chevron on the floor ahead of the vehicle
            if (mode == HologramStageMode.GUIDING_TO_VEHICLE) {
                drawBearingPointer(
                    center = floorCenter,
                    outerRadius = baseRadius,
                    perspectiveY = perspectiveY,
                    bearingDeg = animatedArrowDeg,
                    accentColor = animatedTint
                )
            }

            // 2. 2.5D Stylized Wireframe (Scooter default or Car)
            val vehicleScale = when (mode) {
                HologramStageMode.GUIDING_TO_VEHICLE -> minDim * 0.21f
                else -> minDim * 0.28f
            }
            val vehicleOrigin = Offset(floorCenter.x, floorCenter.y - hoverLiftPx)
            val wireframeAlpha = if (mode == HologramStageMode.READY_TO_PARK) 0.84f else 1.0f

            if (vehicleType == VehicleHologramType.CAR) {
                drawCarWireframe25D(
                    origin = vehicleOrigin,
                    scale = vehicleScale,
                    yawDeg = vehicleYawDeg,
                    pitchTiltDeg = tiltPitchDeg,
                    rollTiltDeg = tiltRollDeg,
                    perspectiveY = perspectiveY,
                    accentColor = animatedTint.copy(alpha = wireframeAlpha),
                    highlightColor = Color(0xFFD8FFF3).copy(alpha = wireframeAlpha)
                )
            } else {
                drawScooterWireframe25D(
                    origin = vehicleOrigin,
                    scale = vehicleScale,
                    yawDeg = vehicleYawDeg,
                    pitchTiltDeg = tiltPitchDeg,
                    rollTiltDeg = tiltRollDeg,
                    perspectiveY = perspectiveY,
                    accentColor = animatedTint.copy(alpha = wireframeAlpha),
                    highlightColor = Color(0xFFD8FFF3).copy(alpha = wireframeAlpha)
                )
            }

            // Stage 2: Single vertical holographic scanline sweep across the vehicle body
            if (mode == HologramStageMode.PARKED_LOCKED && showScanline) {
                val scanY = vehicleOrigin.y - vehicleScale * 1.15f * (1f - scanlineProgress.value)
                val scanHalfW = baseRadius * 0.62f
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFFD8FFF3),
                            animatedTint,
                            Color.Transparent
                        ),
                        startX = floorCenter.x - scanHalfW,
                        endX = floorCenter.x + scanHalfW
                    ),
                    start = Offset(floorCenter.x - scanHalfW, scanY),
                    end = Offset(floorCenter.x + scanHalfW, scanY),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun DrawScope.drawPerspectiveRadarFloor(
    center: Offset,
    outerRadius: Float,
    perspectiveY: Float,
    accentColor: Color,
    compassRotationDeg: Float,
    isLowAccuracy: Boolean,
    isVeryClose: Boolean,
    isAligned: Boolean
) {
    val dashEffect = if (isLowAccuracy) {
        PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
    } else {
        null
    }

    val ringFactors = listOf(1.0f, 0.72f, 0.44f)
    ringFactors.forEachIndexed { idx, factor ->
        val r = outerRadius * factor
        val isOuter = idx == 0
        val ringColor = when {
            isVeryClose -> MintAccent.copy(alpha = if (isOuter) 0.65f else 0.35f)
            isLowAccuracy -> WarningAmber.copy(alpha = 0.36f)
            isAligned && isOuter -> MintAccent.copy(alpha = 0.85f)
            isOuter -> accentColor.copy(alpha = 0.48f)
            else -> OutlineSubtle.copy(alpha = 0.75f)
        }
        drawOval(
            color = ringColor,
            topLeft = Offset(center.x - r, center.y - r * perspectiveY),
            size = Size(r * 2f, r * 2f * perspectiveY),
            style = Stroke(
                width = if (isOuter && isAligned) 2.2.dp.toPx() else 1.1.dp.toPx(),
                pathEffect = dashEffect
            )
        )
    }

    // Radial perspective grid lines (every 45°, rotated by compassRotationDeg)
    for (deg in 0 until 360 step 45) {
        val rad = Math.toRadians((deg + compassRotationDeg).toDouble() - 90.0)
        val innerR = outerRadius * 0.20f
        drawLine(
            color = OutlineSubtle.copy(alpha = 0.48f),
            start = Offset(
                center.x + (innerR * cos(rad)).toFloat(),
                center.y + (innerR * sin(rad) * perspectiveY).toFloat()
            ),
            end = Offset(
                center.x + (outerRadius * cos(rad)).toFloat(),
                center.y + (outerRadius * sin(rad) * perspectiveY).toFloat()
            ),
            strokeWidth = 0.9.dp.toPx()
        )
    }

    // Cardinal compass tick marks & Vietnamese labels: B (Bắc), Đ (Đông), N (Nam), T (Tây)
    val labelPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = 10.sp.toPx()
        color = android.graphics.Color.argb(190, 154, 165, 177)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val cardinals = listOf(0 to "B", 90 to "Đ", 180 to "N", 270 to "T")
    cardinals.forEach { (deg, label) ->
        val rad = Math.toRadians((deg + compassRotationDeg).toDouble() - 90.0)
        val tickInner = outerRadius * 0.91f
        val tickOuter = outerRadius * 1.01f
        drawLine(
            color = if (deg == 0) accentColor.copy(alpha = 0.85f) else TextSecondary.copy(alpha = 0.65f),
            start = Offset(
                center.x + (tickInner * cos(rad)).toFloat(),
                center.y + (tickInner * sin(rad) * perspectiveY).toFloat()
            ),
            end = Offset(
                center.x + (tickOuter * cos(rad)).toFloat(),
                center.y + (tickOuter * sin(rad) * perspectiveY).toFloat()
            ),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )

        val labelR = outerRadius * 1.12f
        val lx = center.x + (labelR * cos(rad)).toFloat()
        val ly = center.y + (labelR * sin(rad) * perspectiveY).toFloat() + 3.5.dp.toPx()
        drawContext.canvas.nativeCanvas.drawText(label, lx, ly, labelPaint)
    }
}

private fun DrawScope.drawSweepSector(
    center: Offset,
    outerRadius: Float,
    perspectiveY: Float,
    sweepAngleDeg: Float,
    color: Color
) {
    val startRad = Math.toRadians(sweepAngleDeg.toDouble() - 90.0)
    val endRad = Math.toRadians((sweepAngleDeg + 42f).toDouble() - 90.0)
    val path = Path().apply {
        moveTo(center.x, center.y)
        val steps = 8
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val r = startRad + (endRad - startRad) * t
            lineTo(
                center.x + (outerRadius * cos(r)).toFloat(),
                center.y + (outerRadius * sin(r) * perspectiveY).toFloat()
            )
        }
        close()
    }
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0.04f)),
            center = center,
            radius = outerRadius
        )
    )
}

private fun DrawScope.drawBearingPointer(
    center: Offset,
    outerRadius: Float,
    perspectiveY: Float,
    bearingDeg: Float,
    accentColor: Color
) {
    val rad = Math.toRadians(bearingDeg.toDouble() - 90.0)
    val tipR = outerRadius * 0.93f
    val baseR = outerRadius * 0.76f
    val spreadRad = 0.13

    val tip = Offset(
        center.x + (tipR * cos(rad)).toFloat(),
        center.y + (tipR * sin(rad) * perspectiveY).toFloat()
    )
    val left = Offset(
        center.x + (baseR * cos(rad - spreadRad)).toFloat(),
        center.y + (baseR * sin(rad - spreadRad) * perspectiveY).toFloat()
    )
    val right = Offset(
        center.x + (baseR * cos(rad + spreadRad)).toFloat(),
        center.y + (baseR * sin(rad + spreadRad) * perspectiveY).toFloat()
    )

    val pointerPath = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path = pointerPath, color = accentColor.copy(alpha = 0.35f))
    drawPath(
        path = pointerPath,
        color = Color(0xFFD8FFF3),
        style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round)
    )
}

private fun project25D(
    p: Vec3,
    origin: Offset,
    scale: Float,
    yawDeg: Float,
    pitchTiltDeg: Float,
    rollTiltDeg: Float,
    perspectiveY: Float
): Offset {
    val yawRad = Math.toRadians(yawDeg.toDouble())
    val cosY = cos(yawRad).toFloat()
    val sinY = sin(yawRad).toFloat()
    val rx = p.x * cosY + p.y * sinY
    val ry = -p.x * sinY + p.y * cosY

    val rollShift = sin(Math.toRadians(rollTiltDeg.toDouble())).toFloat() * 0.35f
    val pitchShift = sin(Math.toRadians(pitchTiltDeg.toDouble())).toFloat() * 0.35f

    val sx = origin.x + (rx + p.z * rollShift) * scale
    val sy = origin.y - p.z * scale * 0.92f - (ry * perspectiveY - p.z * pitchShift) * scale
    return Offset(sx, sy)
}

private fun DrawScope.drawScooterWireframe25D(
    origin: Offset,
    scale: Float,
    yawDeg: Float,
    pitchTiltDeg: Float,
    rollTiltDeg: Float,
    perspectiveY: Float,
    accentColor: Color,
    highlightColor: Color
) {
    fun proj(x: Float, y: Float, z: Float): Offset = project25D(
        Vec3(x, y, z), origin, scale, yawDeg, pitchTiltDeg, rollTiltDeg, perspectiveY
    )

    fun strokePoly(points: List<Vec3>, color: Color, widthDp: Float, closed: Boolean = false) {
        if (points.size < 2) return
        val path = Path().apply {
            val first = proj(points[0].x, points[0].y, points[0].z)
            moveTo(first.x, first.y)
            for (i in 1 until points.size) {
                val pt = proj(points[i].x, points[i].y, points[i].z)
                lineTo(pt.x, pt.y)
            }
            if (closed) close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    fun drawWheel(centerY: Float, radius: Float) {
        val segments = 14
        val pts = (0 until segments).map { i ->
            val a = (2.0 * PI * i / segments)
            Vec3(0f, centerY + (radius * cos(a)).toFloat(), radius + (radius * sin(a)).toFloat())
        }
        strokePoly(pts, accentColor.copy(alpha = 0.95f), 2.1f, closed = true)
        val hub = proj(0f, centerY, radius)
        drawCircle(color = accentColor.copy(alpha = 0.32f), radius = scale * radius * 0.55f, center = hub)
        drawCircle(color = highlightColor, radius = 2.6.dp.toPx(), center = hub)
    }

    // Headlight beam cone extending forward (+y)
    val headOrigin = proj(0f, 0.72f, 0.88f)
    val beamLeft = proj(-0.34f, 1.55f, 0.05f)
    val beamRight = proj(0.34f, 1.55f, 0.05f)
    val beamPath = Path().apply {
        moveTo(headOrigin.x, headOrigin.y)
        lineTo(beamLeft.x, beamLeft.y)
        lineTo(beamRight.x, beamRight.y)
        close()
    }
    drawPath(
        path = beamPath,
        brush = Brush.linearGradient(
            colors = listOf(highlightColor.copy(alpha = 0.28f), Color.Transparent),
            start = headOrigin,
            end = Offset((beamLeft.x + beamRight.x) * 0.5f, (beamLeft.y + beamRight.y) * 0.5f)
        )
    )

    // Front & Rear Wheels
    drawWheel(centerY = 0.72f, radius = 0.25f)
    drawWheel(centerY = -0.68f, radius = 0.24f)

    // Floorboard & chassis spine
    strokePoly(
        listOf(
            Vec3(-0.14f, 0.42f, 0.22f),
            Vec3(-0.14f, -0.18f, 0.22f),
            Vec3(-0.16f, -0.78f, 0.52f),
            Vec3(0.16f, -0.78f, 0.52f),
            Vec3(0.14f, -0.18f, 0.22f),
            Vec3(0.14f, 0.42f, 0.22f)
        ),
        accentColor,
        1.6f
    )

    // Curved front shield / apron (yếm xe cong)
    val leftApron = listOf(
        Vec3(-0.18f, 0.42f, 0.24f),
        Vec3(-0.22f, 0.60f, 0.56f),
        Vec3(-0.15f, 0.66f, 0.88f),
        Vec3(0f, 0.70f, 0.96f)
    )
    val rightApron = listOf(
        Vec3(0.18f, 0.42f, 0.24f),
        Vec3(0.22f, 0.60f, 0.56f),
        Vec3(0.15f, 0.66f, 0.88f),
        Vec3(0f, 0.70f, 0.96f)
    )
    strokePoly(leftApron, accentColor, 1.7f)
    strokePoly(rightApron, accentColor, 1.7f)
    strokePoly(
        listOf(
            Vec3(0f, 0.76f, 0.36f),
            Vec3(0f, 0.74f, 0.68f),
            Vec3(0f, 0.66f, 0.98f)
        ),
        highlightColor,
        1.8f
    )

    // Stepped seat (yên xe 2 tầng)
    strokePoly(
        listOf(
            Vec3(-0.14f, -0.05f, 0.66f),
            Vec3(-0.15f, -0.34f, 0.66f),
            Vec3(-0.15f, -0.44f, 0.74f),
            Vec3(-0.12f, -0.76f, 0.74f),
            Vec3(0.12f, -0.76f, 0.74f),
            Vec3(0.15f, -0.44f, 0.74f),
            Vec3(0.15f, -0.34f, 0.66f),
            Vec3(0.14f, -0.05f, 0.66f)
        ),
        highlightColor,
        1.7f,
        closed = true
    )

    // Steering column, handlebar & twin mirrors
    strokePoly(
        listOf(Vec3(0f, 0.55f, 0.56f), Vec3(0f, 0.48f, 1.08f)),
        accentColor,
        1.8f
    )
    strokePoly(
        listOf(
            Vec3(-0.34f, 0.44f, 1.07f),
            Vec3(-0.12f, 0.48f, 1.10f),
            Vec3(0.12f, 0.48f, 1.10f),
            Vec3(0.34f, 0.44f, 1.07f)
        ),
        highlightColor,
        2.0f
    )
    // Left & right mirrors
    strokePoly(listOf(Vec3(-0.24f, 0.46f, 1.08f), Vec3(-0.31f, 0.45f, 1.24f)), accentColor, 1.4f)
    strokePoly(listOf(Vec3(0.24f, 0.46f, 1.08f), Vec3(0.31f, 0.45f, 1.24f)), accentColor, 1.4f)
    drawCircle(color = highlightColor, radius = 2.5.dp.toPx(), center = proj(-0.31f, 0.45f, 1.25f))
    drawCircle(color = highlightColor, radius = 2.5.dp.toPx(), center = proj(0.31f, 0.45f, 1.25f))

    // Headlight glow node
    drawCircle(color = highlightColor, radius = 3.2.dp.toPx(), center = headOrigin)
}

private fun DrawScope.drawCarWireframe25D(
    origin: Offset,
    scale: Float,
    yawDeg: Float,
    pitchTiltDeg: Float,
    rollTiltDeg: Float,
    perspectiveY: Float,
    accentColor: Color,
    highlightColor: Color
) {
    fun proj(x: Float, y: Float, z: Float): Offset = project25D(
        Vec3(x, y, z), origin, scale, yawDeg, pitchTiltDeg, rollTiltDeg, perspectiveY
    )

    fun strokePoly(points: List<Vec3>, color: Color, widthDp: Float, closed: Boolean = false) {
        val path = Path().apply {
            val first = proj(points[0].x, points[0].y, points[0].z)
            moveTo(first.x, first.y)
            for (i in 1 until points.size) {
                val pt = proj(points[i].x, points[i].y, points[i].z)
                lineTo(pt.x, pt.y)
            }
            if (closed) close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    // Twin headlight beams
    listOf(-0.30f, 0.30f).forEach { hx ->
        val hOrigin = proj(hx, 0.92f, 0.34f)
        val bL = proj(hx - 0.16f, 1.55f, 0.04f)
        val bR = proj(hx + 0.16f, 1.55f, 0.04f)
        val path = Path().apply {
            moveTo(hOrigin.x, hOrigin.y)
            lineTo(bL.x, bL.y)
            lineTo(bR.x, bR.y)
            close()
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(highlightColor.copy(alpha = 0.25f), Color.Transparent),
                start = hOrigin,
                end = Offset((bL.x + bR.x) * 0.5f, (bL.y + bR.y) * 0.5f)
            )
        )
        drawCircle(color = highlightColor, radius = 2.6.dp.toPx(), center = hOrigin)
    }

    // Lower body chassis
    val lowerBody = listOf(
        Vec3(-0.42f, 0.92f, 0.22f),
        Vec3(0.42f, 0.92f, 0.22f),
        Vec3(0.42f, -0.92f, 0.22f),
        Vec3(-0.42f, -0.92f, 0.22f)
    )
    strokePoly(lowerBody, accentColor, 1.6f, closed = true)

    // Beltline (hood + trunk + fenders)
    val beltline = listOf(
        Vec3(-0.42f, 0.90f, 0.44f),
        Vec3(0.42f, 0.90f, 0.44f),
        Vec3(0.42f, -0.90f, 0.48f),
        Vec3(-0.42f, -0.90f, 0.48f)
    )
    strokePoly(beltline, accentColor, 1.7f, closed = true)

    // Cabin roof
    val roof = listOf(
        Vec3(-0.32f, 0.26f, 0.80f),
        Vec3(0.32f, 0.26f, 0.80f),
        Vec3(0.32f, -0.48f, 0.78f),
        Vec3(-0.32f, -0.48f, 0.78f)
    )
    strokePoly(roof, highlightColor, 1.8f, closed = true)

    // Pillars connecting beltline to roof
    val pillars = listOf(
        Vec3(-0.40f, 0.52f, 0.45f) to Vec3(-0.32f, 0.26f, 0.80f),
        Vec3(0.40f, 0.52f, 0.45f) to Vec3(0.32f, 0.26f, 0.80f),
        Vec3(-0.40f, -0.74f, 0.48f) to Vec3(-0.32f, -0.48f, 0.78f),
        Vec3(0.40f, -0.74f, 0.48f) to Vec3(0.32f, -0.48f, 0.78f)
    )
    pillars.forEach { (a, b) -> strokePoly(listOf(a, b), highlightColor, 1.5f) }

    // 4 wheel hubs
    listOf(
        Vec3(-0.42f, 0.58f, 0.18f),
        Vec3(0.42f, 0.58f, 0.18f),
        Vec3(-0.42f, -0.58f, 0.18f),
        Vec3(0.42f, -0.58f, 0.18f)
    ).forEach { w ->
        val hub = proj(w.x, w.y, w.z)
        drawCircle(color = accentColor.copy(alpha = 0.35f), radius = 5.dp.toPx(), center = hub)
        drawCircle(color = highlightColor, radius = 2.4.dp.toPx(), center = hub)
    }
}
