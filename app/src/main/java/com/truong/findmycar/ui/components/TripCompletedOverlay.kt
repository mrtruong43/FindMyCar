package com.truong.findmycar.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truong.findmycar.R
import com.truong.findmycar.ui.theme.MintAccent
import com.truong.findmycar.ui.theme.NearBlackBg
import com.truong.findmycar.ui.theme.OnMintAccent
import com.truong.findmycar.ui.theme.SurfaceLayer1
import com.truong.findmycar.ui.theme.SurfaceLayer2
import com.truong.findmycar.ui.theme.TextPrimary
import com.truong.findmycar.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TripCompletedOverlay(
    savedAtMs: Long,
    note: String,
    hapticEnabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var dismissed by remember { mutableStateOf(false) }
    fun finishOnce() {
        if (!dismissed) {
            dismissed = true
            onDismiss()
        }
    }

    val badgeScale = remember { Animatable(0.25f) }
    val sparkProgress = remember { Animatable(0f) }
    val autoDismissProgress = remember { Animatable(0f) }

    val durationText = remember(savedAtMs) {
        formatParkingDuration(context, savedAtMs)
    }

    LaunchedEffect(Unit) {
        if (hapticEnabled) {
            triggerCelebrationHaptic(context) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
        launch {
            badgeScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.50f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
        launch {
            sparkProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing)
            )
        }
        autoDismissProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2_500, easing = LinearEasing)
        )
        finishOnce()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "celebration_rings")
    val ringWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_wave"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NearBlackBg.copy(alpha = 0.92f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* absorb backdrop taps */ }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = SurfaceLayer1,
            border = BorderStroke(1.2.dp, MintAccent.copy(alpha = 0.50f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Animated Checkmark Badge + Expanding Concentric Radar Rings + Particle Sparks
                Box(
                    modifier = Modifier.size(136.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerPt = center
                        val maxR = size.minDimension * 0.48f

                        // Ambient emerald glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MintAccent.copy(alpha = 0.24f),
                                    Color.Transparent
                                ),
                                center = centerPt,
                                radius = maxR
                            ),
                            center = centerPt,
                            radius = maxR
                        )

                        // Expanding concentric radar rings
                        listOf(0f, 0.5f).forEach { offset ->
                            val phase = (ringWave + offset) % 1f
                            val r = maxR * (0.45f + phase * 0.55f)
                            val alpha = (1f - phase) * 0.55f
                            drawCircle(
                                color = MintAccent.copy(alpha = alpha),
                                radius = r,
                                center = centerPt,
                                style = Stroke(width = 1.6.dp.toPx())
                            )
                        }

                        // Minimalist emerald particle sparks
                        val sparkP = sparkProgress.value
                        if (sparkP in 0.02f..0.98f) {
                            val sparkAlpha = (1f - sparkP) * 0.9f
                            val sparkCount = 8
                            for (i in 0 until sparkCount) {
                                val angle = (2.0 * PI * i / sparkCount) + (PI / 8.0)
                                val startR = maxR * (0.50f + sparkP * 0.28f)
                                val endR = startR + 8.dp.toPx() * (1f - sparkP * 0.4f)
                                drawLine(
                                    color = if (i % 2 == 0) MintAccent.copy(alpha = sparkAlpha)
                                    else Color(0xFF80FFF0).copy(alpha = sparkAlpha),
                                    start = Offset(
                                        centerPt.x + (startR * cos(angle)).toFloat(),
                                        centerPt.y + (startR * sin(angle)).toFloat()
                                    ),
                                    end = Offset(
                                        centerPt.x + (endR * cos(angle)).toFloat(),
                                        centerPt.y + (endR * sin(angle)).toFloat()
                                    ),
                                    strokeWidth = 2.2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .scale(badgeScale.value)
                            .clip(CircleShape)
                            .background(MintAccent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = OnMintAccent,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // Headline
                Text(
                    text = stringResource(R.string.trip_completed_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                // Compact Trip Summary Pill (duration + saved floor/pillar note if present)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = SurfaceLayer2,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MintAccent,
                            textAlign = TextAlign.Center
                        )
                        if (note.isNotBlank()) {
                            Text(
                                text = note.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Primary "Hoàn tất" button with 2.5s linear auto-dismiss progress bar
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { finishOnce() },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MintAccent,
                            contentColor = OnMintAccent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.trip_completed_done_button),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    LinearProgressIndicator(
                        progress = { 1f - autoDismissProgress.value },
                        color = MintAccent,
                        trackColor = SurfaceLayer2,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }
    }
}

private fun triggerCelebrationHaptic(
    context: Context,
    fallback: () -> Unit
) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator != null && vibrator.hasVibrator()) {
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(45L, 200))
        }.onFailure { fallback() }
    } else {
        fallback()
    }
}
