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
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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

/**
 * 3D vehicle-local coordinate:
 * - x: longitudinal (+x = front, -x = rear)
 * - y: vertical (+y = up, 0 = ground)
 * - z: lateral (+z = right, -z = left)
 */
private data class Vec3(val x: Float, val y: Float, val z: Float)

private val HologramHighlight = Color(0xFF80FFF0)
private val HologramCoreWhite = Color(0xFFD8FFF8)

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

    // Stage 2: Tap 360° showroom spin + Interactive Touch-to-Rotate (drag + inertia)
    val spinDeg = remember { Animatable(0f) }
    val dragYawAnim = remember { Animatable(0f) }
    val dragPitchAnim = remember { Animatable(0f) }

    val dragModifier = Modifier.pointerInput(mode) {
        val velocityTracker = VelocityTracker()
        detectDragGestures(
            onDragStart = {
                velocityTracker.resetTracking()
                scope.launch {
                    dragYawAnim.stop()
                    dragPitchAnim.stop()
                }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                scope.launch {
                    dragYawAnim.snapTo(dragYawAnim.value + dragAmount.x * 0.45f)
                    dragPitchAnim.snapTo((dragPitchAnim.value + dragAmount.y * 0.14f).coerceIn(-16f, 16f))
                }
            },
            onDragEnd = {
                val velX = velocityTracker.calculateVelocity().x
                scope.launch {
                    if (mode == HologramStageMode.GUIDING_TO_VEHICLE) {
                        // Return smoothly to live target compass bearing
                        launch {
                            dragYawAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.72f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    } else {
                        // Apply smooth rotational inertia on release
                        launch {
                            dragYawAnim.animateDecay(
                                initialVelocity = velX * 0.42f,
                                animationSpec = exponentialDecay(frictionMultiplier = 1.8f)
                            )
                        }
                    }
                    // Gently blend pitch tilt back to ambient gyroscope tilt
                    launch {
                        dragPitchAnim.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 0.75f,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                }
            },
            onDragCancel = {
                scope.launch {
                    if (mode == HologramStageMode.GUIDING_TO_VEHICLE) {
                        launch {
                            dragYawAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.75f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                    launch {
                        dragPitchAnim.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 0.75f,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                }
            }
        )
    }

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
            .then(tapModifier)
            .then(dragModifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = size.minDimension
            val perspectiveY = 0.46f
            val baseRadius = minOf(size.width * 0.46f, size.height * 0.68f) * radarZoom *
                (if (mode == HologramStageMode.PARKED_LOCKED) ringTightenScale.value else 1f)

            val floorCenterY = when (mode) {
                HologramStageMode.GUIDING_TO_VEHICLE -> center.y + minDim * 0.06f
                else -> center.y + size.height * 0.17f
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

            // Isometric ~35° yaw for showroom stages; live bearing yaw for GUIDING_TO_VEHICLE
            val vehicleYawDeg = when (mode) {
                HologramStageMode.READY_TO_PARK -> 35f + dragYawAnim.value
                HologramStageMode.PARKED_LOCKED -> 35f + spinDeg.value + dragYawAnim.value
                HologramStageMode.GUIDING_TO_VEHICLE -> animatedArrowDeg + dragYawAnim.value
            }
            val effectivePitchTilt = (tiltPitchDeg + dragPitchAnim.value).coerceIn(-16f, 16f)

            val hoverLiftPx = when (mode) {
                HologramStageMode.READY_TO_PARK -> (sin(hoverPhase) * 3f + 4f).dp.toPx()
                HologramStageMode.PARKED_LOCKED -> lockOffsetDp.value.dp.toPx()
                HologramStageMode.GUIDING_TO_VEHICLE -> 2.dp.toPx()
            }

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

            // 2. Volumetric 3D Isometric Vehicle Mesh (~1.65x scaled up)
            val vehicleScale = when (mode) {
                HologramStageMode.GUIDING_TO_VEHICLE -> minDim * 0.30f
                else -> minOf(size.width * 0.34f, size.height * 0.44f)
            }
            val vehicleOrigin = Offset(floorCenter.x, floorCenter.y - hoverLiftPx)
            val alphaScale = if (mode == HologramStageMode.READY_TO_PARK) 0.90f else 1.0f

            if (vehicleType == VehicleHologramType.CAR) {
                drawVolumetricCar3D(
                    origin = vehicleOrigin,
                    floorOrigin = floorCenter,
                    scale = vehicleScale,
                    yawDeg = vehicleYawDeg,
                    pitchTiltDeg = effectivePitchTilt,
                    rollTiltDeg = tiltRollDeg,
                    accentColor = animatedTint,
                    alphaScale = alphaScale
                )
            } else {
                drawVolumetricScooter3D(
                    origin = vehicleOrigin,
                    floorOrigin = floorCenter,
                    scale = vehicleScale,
                    yawDeg = vehicleYawDeg,
                    pitchTiltDeg = effectivePitchTilt,
                    rollTiltDeg = tiltRollDeg,
                    accentColor = animatedTint,
                    alphaScale = alphaScale
                )
            }

            // Stage 2: Single vertical holographic scanline sweep across the vehicle body
            if (mode == HologramStageMode.PARKED_LOCKED && showScanline) {
                val scanY = vehicleOrigin.y - vehicleScale * 1.25f * (1f - scanlineProgress.value)
                val scanHalfW = baseRadius * 0.64f
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            HologramHighlight,
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
        color = HologramCoreWhite,
        style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round)
    )
}

/**
 * True 3D isometric projection helper:
 * - Rotated by [yawDeg] around vertical Y axis
 * - Projected at ~22° isometric pitch (+ gyroscope tilt)
 */
private fun project3D(
    p: Vec3,
    origin: Offset,
    scale: Float,
    yawDeg: Float,
    pitchTiltDeg: Float,
    rollTiltDeg: Float
): Offset {
    val yawRad = Math.toRadians(yawDeg.toDouble())
    val sinY = sin(yawRad).toFloat()
    val cosY = cos(yawRad).toFloat()

    // Rotate (x, z) by yaw around vertical Y axis
    val rx = p.x * sinY + p.z * cosY
    val rz = p.x * cosY - p.z * sinY

    val pitchRad = Math.toRadians((22.0 + pitchTiltDeg * 0.45).coerceIn(10.0, 36.0))
    val cosP = cos(pitchRad).toFloat()
    val sinP = sin(pitchRad).toFloat()

    val rollShift = sin(Math.toRadians(rollTiltDeg.toDouble())).toFloat() * 0.32f

    val sx = origin.x + (rx + p.y * rollShift) * scale
    val sy = origin.y - (p.y * cosP + rz * sinP) * scale
    return Offset(sx, sy)
}

/**
 * Computes view-space depth (larger = closer to viewer) for simple painter's sorting of ±z sides.
 */
private fun depth3D(p: Vec3, yawDeg: Float): Float {
    val yawRad = Math.toRadians(yawDeg.toDouble())
    val rz = p.x * cos(yawRad).toFloat() - p.z * sin(yawRad).toFloat()
    return -rz
}

private fun DrawScope.drawVolumetricScooter3D(
    origin: Offset,
    floorOrigin: Offset,
    scale: Float,
    yawDeg: Float,
    pitchTiltDeg: Float,
    rollTiltDeg: Float,
    accentColor: Color,
    alphaScale: Float
) {
    fun p(x: Float, y: Float, z: Float): Offset =
        project3D(Vec3(x, y, z), origin, scale, yawDeg, pitchTiltDeg, rollTiltDeg)

    fun pFloor(x: Float, z: Float): Offset =
        project3D(Vec3(x, 0f, z), floorOrigin, scale, yawDeg, pitchTiltDeg, rollTiltDeg)

    fun pathFrom(pts: List<Vec3>, closed: Boolean = false): Path = Path().apply {
        val first = p(pts[0].x, pts[0].y, pts[0].z)
        moveTo(first.x, first.y)
        for (i in 1 until pts.size) {
            val pt = p(pts[i].x, pts[i].y, pts[i].z)
            lineTo(pt.x, pt.y)
        }
        if (closed) close()
    }

    fun strokePts(pts: List<Vec3>, color: Color, widthDp: Float, closed: Boolean = false) {
        drawPath(
            path = pathFrom(pts, closed),
            color = color,
            style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    fun draw3DWheel(centerX: Float, radius: Float, halfWidth: Float) {
        val segments = 16
        val nearSign = if (depth3D(Vec3(centerX, radius, halfWidth), yawDeg) >=
            depth3D(Vec3(centerX, radius, -halfWidth), yawDeg)
        ) 1f else -1f
        val farSign = -nearSign

        val farSide = (0 until segments).map { i ->
            val a = 2.0 * PI * i / segments
            Vec3(centerX + (radius * cos(a)).toFloat(), radius + (radius * sin(a)).toFloat(), farSign * halfWidth)
        }
        val nearSide = (0 until segments).map { i ->
            val a = 2.0 * PI * i / segments
            Vec3(centerX + (radius * cos(a)).toFloat(), radius + (radius * sin(a)).toFloat(), nearSign * halfWidth)
        }

        // Translucent tire volume fill
        drawPath(
            path = pathFrom(farSide, closed = true),
            color = accentColor.copy(alpha = 0.14f * alphaScale)
        )
        strokePts(farSide, accentColor.copy(alpha = 0.45f * alphaScale), 1.0f, closed = true)

        // Tread cross-ribs connecting far & near tire walls
        for (i in 0 until segments step 2) {
            strokePts(listOf(farSide[i], nearSide[i]), accentColor.copy(alpha = 0.48f * alphaScale), 0.75f)
        }

        drawPath(
            path = pathFrom(nearSide, closed = true),
            brush = Brush.radialGradient(
                colors = listOf(
                    HologramHighlight.copy(alpha = 0.24f * alphaScale),
                    accentColor.copy(alpha = 0.12f * alphaScale)
                ),
                center = p(centerX, radius, nearSign * halfWidth),
                radius = radius * scale * 1.1f
            )
        )
        strokePts(nearSide, accentColor.copy(alpha = 0.95f * alphaScale), 1.6f, closed = true)

        // Inner glowing rim + hubcap
        val rimRadius = radius * 0.56f
        val rimPts = (0 until 12).map { i ->
            val a = 2.0 * PI * i / 12
            Vec3(centerX + (rimRadius * cos(a)).toFloat(), radius + (rimRadius * sin(a)).toFloat(), nearSign * halfWidth)
        }
        strokePts(rimPts, HologramHighlight.copy(alpha = 0.85f * alphaScale), 1.0f, closed = true)
        val hub = p(centerX, radius, nearSign * (halfWidth + 0.01f))
        drawCircle(color = accentColor.copy(alpha = 0.35f * alphaScale), radius = 5.dp.toPx(), center = hub)
        drawCircle(color = HologramCoreWhite.copy(alpha = alphaScale), radius = 2.3.dp.toPx(), center = hub)
    }

    // Ground ambient shadow directly beneath front & rear tires
    listOf(0.72f, -0.68f).forEach { wx ->
        val c = pFloor(wx, 0f)
        val rx = scale * 0.34f
        val ry = rx * 0.46f
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(accentColor.copy(alpha = 0.34f * alphaScale), Color.Transparent),
                center = c,
                radius = rx
            ),
            topLeft = Offset(c.x - rx, c.y - ry),
            size = Size(rx * 2f, ry * 2f)
        )
    }

    // Soft volumetric headlight beam cone projecting forward onto the radar floor
    val headOrigin = p(0.74f, 0.96f, 0f)
    val beamLeft = pFloor(1.62f, -0.34f)
    val beamRight = pFloor(1.62f, 0.34f)
    val beamPath = Path().apply {
        moveTo(headOrigin.x, headOrigin.y)
        lineTo(beamLeft.x, beamLeft.y)
        lineTo(beamRight.x, beamRight.y)
        close()
    }
    drawPath(
        path = beamPath,
        brush = Brush.linearGradient(
            colors = listOf(HologramHighlight.copy(alpha = 0.28f * alphaScale), Color.Transparent),
            start = headOrigin,
            end = Offset((beamLeft.x + beamRight.x) * 0.5f, (beamLeft.y + beamRight.y) * 0.5f)
        )
    )

    // Painter order for left (-z) vs right (+z) side cowls
    val rightFirst = depth3D(Vec3(0f, 0.5f, 0.28f), yawDeg) <
        depth3D(Vec3(0f, 0.5f, -0.28f), yawDeg)
    val sideOrder = if (rightFirst) listOf(1f, -1f) else listOf(-1f, 1f)

    // 1. Thick Front & Rear Wheels
    draw3DWheel(centerX = 0.72f, radius = 0.24f, halfWidth = 0.095f)
    draw3DWheel(centerX = -0.68f, radius = 0.23f, halfWidth = 0.105f)

    // 2. Flat Step-Through Floorboard (extruded ±z deck)
    val floorTopPts = listOf(
        Vec3(0.42f, 0.27f, -0.24f),
        Vec3(0.42f, 0.27f, 0.24f),
        Vec3(-0.20f, 0.27f, 0.25f),
        Vec3(-0.20f, 0.27f, -0.25f)
    )
    val floorBottomPts = listOf(
        Vec3(0.42f, 0.20f, -0.22f),
        Vec3(0.42f, 0.20f, 0.22f),
        Vec3(-0.20f, 0.20f, 0.23f),
        Vec3(-0.20f, 0.20f, -0.23f)
    )
    drawPath(
        path = pathFrom(floorTopPts, closed = true),
        color = accentColor.copy(alpha = 0.18f * alphaScale)
    )
    strokePts(floorBottomPts, accentColor.copy(alpha = 0.55f * alphaScale), 0.9f, closed = true)
    strokePts(floorTopPts, accentColor.copy(alpha = 0.90f * alphaScale), 1.4f, closed = true)
    listOf(0.28f, 0.10f, -0.08f).forEach { fx ->
        strokePts(
            listOf(Vec3(fx, 0.27f, -0.24f), Vec3(fx, 0.27f, 0.24f)),
            HologramHighlight.copy(alpha = 0.45f * alphaScale),
            0.75f
        )
    }

    // 3. Bulbous Vespa-Style Rear Body Cowls (far side -> ribs -> near side)
    fun rearCowlPath(zSign: Float): Path {
        val p0 = p(-0.18f, 0.30f, zSign * 0.24f)
        val c1 = p(-0.24f, 0.72f, zSign * 0.30f)
        val c2 = p(-0.62f, 0.78f, zSign * 0.33f)
        val pTopRear = p(-0.96f, 0.56f, zSign * 0.17f)
        val c3 = p(-0.96f, 0.34f, zSign * 0.18f)
        val c4 = p(-0.56f, 0.24f, zSign * 0.32f)
        return Path().apply {
            moveTo(p0.x, p0.y)
            cubicTo(c1.x, c1.y, c2.x, c2.y, pTopRear.x, pTopRear.y)
            cubicTo(c3.x, c3.y, c4.x, c4.y, p0.x, p0.y)
            close()
        }
    }

    sideOrder.forEachIndexed { idx, zSign ->
        val isNear = idx == 1
        val cowlPath = rearCowlPath(zSign)
        drawPath(
            path = cowlPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    HologramHighlight.copy(alpha = (if (isNear) 0.26f else 0.12f) * alphaScale),
                    accentColor.copy(alpha = (if (isNear) 0.18f else 0.08f) * alphaScale)
                ),
                start = p(-0.45f, 0.78f, zSign * 0.30f),
                end = p(-0.55f, 0.28f, zSign * 0.30f)
            )
        )
        drawPath(
            path = cowlPath,
            color = (if (isNear) accentColor else accentColor.copy(alpha = 0.55f)).copy(alpha = alphaScale),
            style = Stroke(width = (if (isNear) 1.5f else 1.0f).dp.toPx(), cap = StrokeCap.Round)
        )
    }

    // 4 curved cross-sectional mesh ribs wrapping over the rear body
    listOf(
        Triple(-0.34f, 0.74f, 0.28f),
        Triple(-0.52f, 0.75f, 0.32f),
        Triple(-0.70f, 0.70f, 0.29f),
        Triple(-0.86f, 0.62f, 0.22f)
    ).forEach { (rx, topY, halfW) ->
        val ribPts = listOf(
            Vec3(rx, 0.36f, -halfW),
            Vec3(rx, topY * 0.78f, -halfW * 1.04f),
            Vec3(rx, topY, 0f),
            Vec3(rx, topY * 0.78f, halfW * 1.04f),
            Vec3(rx, 0.36f, halfW)
        )
        strokePts(ribPts, HologramHighlight.copy(alpha = 0.52f * alphaScale), 0.75f)
    }

    // Glowing tail light bar
    strokePts(
        listOf(Vec3(-0.97f, 0.54f, -0.09f), Vec3(-0.97f, 0.54f, 0.09f)),
        Color(0xFFFF7B84).copy(alpha = 0.90f * alphaScale),
        2.2f
    )

    // 4. Wide Curved Front Apron / Leg Shield + Front Fender
    fun frontShieldPath(): Path {
        val topL = p(0.56f, 0.98f, -0.14f)
        val topR = p(0.56f, 0.98f, 0.14f)
        val midR = p(0.68f, 0.60f, 0.27f)
        val botR = p(0.46f, 0.26f, 0.24f)
        val botL = p(0.46f, 0.26f, -0.24f)
        val midL = p(0.68f, 0.60f, -0.27f)
        return Path().apply {
            moveTo(topL.x, topL.y)
            lineTo(topR.x, topR.y)
            cubicTo(midR.x, midR.y, midR.x, midR.y, botR.x, botR.y)
            lineTo(botL.x, botL.y)
            cubicTo(midL.x, midL.y, midL.x, midL.y, topL.x, topL.y)
            close()
        }
    }

    val shieldPath = frontShieldPath()
    drawPath(
        path = shieldPath,
        brush = Brush.linearGradient(
            colors = listOf(
                HologramHighlight.copy(alpha = 0.30f * alphaScale),
                accentColor.copy(alpha = 0.14f * alphaScale)
            ),
            start = p(0.60f, 0.98f, 0f),
            end = p(0.48f, 0.26f, 0f)
        )
    )
    drawPath(
        path = shieldPath,
        color = accentColor.copy(alpha = 0.95f * alphaScale),
        style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round)
    )

    // Central horn-crest ridge ("cà vạt" Vespa) & horizontal shield ribs
    strokePts(
        listOf(
            Vec3(0.60f, 0.97f, 0f),
            Vec3(0.73f, 0.72f, 0f),
            Vec3(0.75f, 0.50f, 0f)
        ),
        HologramCoreWhite.copy(alpha = 0.92f * alphaScale),
        1.5f
    )
    listOf(0.80f to 0.20f, 0.58f to 0.26f).forEach { (ry, hw) ->
        strokePts(
            listOf(Vec3(0.60f, ry, -hw), Vec3(0.72f, ry, 0f), Vec3(0.60f, ry, hw)),
            HologramHighlight.copy(alpha = 0.50f * alphaScale),
            0.75f
        )
    }

    // Integrated front fender arching over front wheel
    val fenderPts = listOf(
        Vec3(0.48f, 0.36f, 0f),
        Vec3(0.66f, 0.52f, 0f),
        Vec3(0.88f, 0.48f, 0f),
        Vec3(0.96f, 0.30f, 0f)
    )
    strokePts(fenderPts, accentColor.copy(alpha = 0.90f * alphaScale), 1.4f)
    strokePts(
        listOf(
            Vec3(0.56f, 0.42f, -0.13f),
            Vec3(0.78f, 0.50f, -0.13f),
            Vec3(0.92f, 0.34f, -0.12f)
        ),
        accentColor.copy(alpha = 0.55f * alphaScale),
        0.9f
    )
    strokePts(
        listOf(
            Vec3(0.56f, 0.42f, 0.13f),
            Vec3(0.78f, 0.50f, 0.13f),
            Vec3(0.92f, 0.34f, 0.12f)
        ),
        accentColor.copy(alpha = 0.55f * alphaScale),
        0.9f
    )

    // 5. Contoured Two-Up Stepped Seat
    val seatTopPts = listOf(
        Vec3(-0.14f, 0.74f, -0.17f),
        Vec3(-0.14f, 0.74f, 0.17f),
        Vec3(-0.46f, 0.77f, 0.19f),
        Vec3(-0.56f, 0.84f, 0.18f),
        Vec3(-0.84f, 0.84f, 0.14f),
        Vec3(-0.84f, 0.84f, -0.14f),
        Vec3(-0.56f, 0.84f, -0.18f),
        Vec3(-0.46f, 0.77f, -0.19f)
    )
    drawPath(
        path = pathFrom(seatTopPts, closed = true),
        brush = Brush.linearGradient(
            colors = listOf(
                HologramHighlight.copy(alpha = 0.32f * alphaScale),
                accentColor.copy(alpha = 0.16f * alphaScale)
            ),
            start = p(-0.80f, 0.85f, 0f),
            end = p(-0.16f, 0.74f, 0f)
        )
    )
    strokePts(seatTopPts, HologramHighlight.copy(alpha = 0.92f * alphaScale), 1.4f, closed = true)
    strokePts(
        listOf(Vec3(-0.46f, 0.77f, -0.19f), Vec3(-0.46f, 0.77f, 0.19f)),
        HologramCoreWhite.copy(alpha = 0.55f * alphaScale),
        0.75f
    )

    // 6. Sculpted Handlebar Cowling, Glowing Visor & Twin Teardrop Mirrors
    val cowlPts = listOf(
        Vec3(0.68f, 1.04f, -0.16f),
        Vec3(0.56f, 1.12f, -0.32f),
        Vec3(0.46f, 1.12f, -0.32f),
        Vec3(0.48f, 1.06f, 0f),
        Vec3(0.46f, 1.12f, 0.32f),
        Vec3(0.56f, 1.12f, 0.32f),
        Vec3(0.68f, 1.04f, 0.16f),
        Vec3(0.72f, 0.98f, 0f)
    )
    drawPath(
        path = pathFrom(cowlPts, closed = true),
        color = HologramHighlight.copy(alpha = 0.24f * alphaScale)
    )
    strokePts(cowlPts, HologramCoreWhite.copy(alpha = 0.95f * alphaScale), 1.5f, closed = true)

    // Glowing instrument visor + elliptical LED headlight
    strokePts(
        listOf(Vec3(0.52f, 1.11f, -0.10f), Vec3(0.58f, 1.13f, 0f), Vec3(0.52f, 1.11f, 0.10f)),
        HologramHighlight.copy(alpha = 0.90f * alphaScale),
        1.6f
    )
    drawCircle(
        color = accentColor.copy(alpha = 0.45f * alphaScale),
        radius = 7.dp.toPx(),
        center = headOrigin
    )
    drawCircle(
        color = HologramCoreWhite.copy(alpha = alphaScale),
        radius = 3.6.dp.toPx(),
        center = headOrigin
    )

    // Twin teardrop mirrors
    listOf(-1f, 1f).forEach { s ->
        strokePts(
            listOf(Vec3(0.56f, 1.12f, s * 0.22f), Vec3(0.55f, 1.30f, s * 0.29f)),
            accentColor.copy(alpha = 0.90f * alphaScale),
            1.2f
        )
        val mirrorCenter = p(0.55f, 1.33f, s * 0.31f)
        drawOval(
            color = HologramHighlight.copy(alpha = 0.30f * alphaScale),
            topLeft = Offset(mirrorCenter.x - 4.5.dp.toPx(), mirrorCenter.y - 3.dp.toPx()),
            size = Size(9.dp.toPx(), 6.dp.toPx())
        )
        drawOval(
            color = HologramCoreWhite.copy(alpha = 0.92f * alphaScale),
            topLeft = Offset(mirrorCenter.x - 4.5.dp.toPx(), mirrorCenter.y - 3.dp.toPx()),
            size = Size(9.dp.toPx(), 6.dp.toPx()),
            style = Stroke(width = 1.2.dp.toPx())
        )
    }
}

private fun DrawScope.drawVolumetricCar3D(
    origin: Offset,
    floorOrigin: Offset,
    scale: Float,
    yawDeg: Float,
    pitchTiltDeg: Float,
    rollTiltDeg: Float,
    accentColor: Color,
    alphaScale: Float
) {
    fun p(x: Float, y: Float, z: Float): Offset =
        project3D(Vec3(x, y, z), origin, scale, yawDeg, pitchTiltDeg, rollTiltDeg)

    fun pFloor(x: Float, z: Float): Offset =
        project3D(Vec3(x, 0f, z), floorOrigin, scale, yawDeg, pitchTiltDeg, rollTiltDeg)

    fun pathFrom(pts: List<Vec3>, closed: Boolean = false): Path = Path().apply {
        val first = p(pts[0].x, pts[0].y, pts[0].z)
        moveTo(first.x, first.y)
        for (i in 1 until pts.size) {
            val pt = p(pts[i].x, pts[i].y, pts[i].z)
            lineTo(pt.x, pt.y)
        }
        if (closed) close()
    }

    fun strokePts(pts: List<Vec3>, color: Color, widthDp: Float, closed: Boolean = false) {
        drawPath(
            path = pathFrom(pts, closed),
            color = color,
            style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    // Ground shadows under front & rear axles
    listOf(0.58f, -0.58f).forEach { wx ->
        val c = pFloor(wx, 0f)
        val rx = scale * 0.56f
        val ry = rx * 0.44f
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(accentColor.copy(alpha = 0.30f * alphaScale), Color.Transparent),
                center = c,
                radius = rx
            ),
            topLeft = Offset(c.x - rx, c.y - ry),
            size = Size(rx * 2f, ry * 2f)
        )
    }

    // Twin headlight beams
    listOf(-0.32f, 0.32f).forEach { hz ->
        val hOrigin = p(0.94f, 0.36f, hz)
        val bL = pFloor(1.62f, hz - 0.16f)
        val bR = pFloor(1.62f, hz + 0.16f)
        val path = Path().apply {
            moveTo(hOrigin.x, hOrigin.y)
            lineTo(bL.x, bL.y)
            lineTo(bR.x, bR.y)
            close()
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(HologramHighlight.copy(alpha = 0.25f * alphaScale), Color.Transparent),
                start = hOrigin,
                end = Offset((bL.x + bR.x) * 0.5f, (bL.y + bR.y) * 0.5f)
            )
        )
        drawCircle(color = HologramCoreWhite.copy(alpha = alphaScale), radius = 2.6.dp.toPx(), center = hOrigin)
    }

    // 4 thick 3D extruded wheels
    listOf(
        0.58f to -0.44f,
        0.58f to 0.44f,
        -0.58f to -0.44f,
        -0.58f to 0.44f
    ).forEach { (wx, wz) ->
        val r = 0.19f
        val outerPts = (0 until 12).map { i ->
            val a = 2.0 * PI * i / 12
            Vec3(wx + (r * cos(a)).toFloat(), r + (r * sin(a)).toFloat(), wz)
        }
        drawPath(path = pathFrom(outerPts, closed = true), color = accentColor.copy(alpha = 0.18f * alphaScale))
        strokePts(outerPts, accentColor.copy(alpha = 0.90f * alphaScale), 1.4f, closed = true)
        val hub = p(wx, r, wz)
        drawCircle(color = HologramHighlight.copy(alpha = 0.85f * alphaScale), radius = 2.2.dp.toPx(), center = hub)
    }

    // Volumetric lower body shell
    val lowerPts = listOf(
        Vec3(0.95f, 0.22f, -0.44f),
        Vec3(0.95f, 0.22f, 0.44f),
        Vec3(-0.92f, 0.24f, 0.44f),
        Vec3(-0.92f, 0.24f, -0.44f)
    )
    val beltPts = listOf(
        Vec3(0.92f, 0.46f, -0.42f),
        Vec3(0.92f, 0.46f, 0.42f),
        Vec3(-0.90f, 0.52f, 0.42f),
        Vec3(-0.90f, 0.52f, -0.42f)
    )
    drawPath(
        path = pathFrom(beltPts, closed = true),
        brush = Brush.linearGradient(
            colors = listOf(
                HologramHighlight.copy(alpha = 0.22f * alphaScale),
                accentColor.copy(alpha = 0.10f * alphaScale)
            ),
            start = p(0.92f, 0.46f, 0f),
            end = p(-0.90f, 0.52f, 0f)
        )
    )
    strokePts(lowerPts, accentColor.copy(alpha = 0.65f * alphaScale), 1.1f, closed = true)
    strokePts(beltPts, accentColor.copy(alpha = 0.95f * alphaScale), 1.5f, closed = true)

    // Cross-sectional body ribs
    listOf(0.55f, 0.10f, -0.45f).forEach { rx ->
        strokePts(
            listOf(Vec3(rx, 0.23f, -0.43f), Vec3(rx, 0.48f, -0.42f), Vec3(rx, 0.48f, 0.42f), Vec3(rx, 0.23f, 0.43f)),
            HologramHighlight.copy(alpha = 0.42f * alphaScale),
            0.75f
        )
    }

    // Greenhouse cabin roof + pillars
    val roofPts = listOf(
        Vec3(0.28f, 0.82f, -0.33f),
        Vec3(0.28f, 0.82f, 0.33f),
        Vec3(-0.48f, 0.80f, 0.33f),
        Vec3(-0.48f, 0.80f, -0.33f)
    )
    drawPath(
        path = pathFrom(roofPts, closed = true),
        color = HologramHighlight.copy(alpha = 0.24f * alphaScale)
    )
    strokePts(roofPts, HologramCoreWhite.copy(alpha = 0.92f * alphaScale), 1.5f, closed = true)
    listOf(
        Vec3(0.56f, 0.47f, -0.40f) to Vec3(0.28f, 0.82f, -0.33f),
        Vec3(0.56f, 0.47f, 0.40f) to Vec3(0.28f, 0.82f, 0.33f),
        Vec3(-0.76f, 0.52f, -0.40f) to Vec3(-0.48f, 0.80f, -0.33f),
        Vec3(-0.76f, 0.52f, 0.40f) to Vec3(-0.48f, 0.80f, 0.33f)
    ).forEach { (a, b) ->
        strokePts(listOf(a, b), HologramHighlight.copy(alpha = 0.88f * alphaScale), 1.3f)
    }

    // Rear LED light bar
    strokePts(
        listOf(Vec3(-0.91f, 0.48f, -0.34f), Vec3(-0.91f, 0.48f, 0.34f)),
        Color(0xFFFF7B84).copy(alpha = 0.88f * alphaScale),
        2.0f
    )
}
