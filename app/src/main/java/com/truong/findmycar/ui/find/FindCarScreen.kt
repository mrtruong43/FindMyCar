package com.truong.findmycar.ui.find

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.truong.findmycar.R
import com.truong.findmycar.guidance.GuidancePhase
import com.truong.findmycar.ui.components.FloorBadge
import com.truong.findmycar.ui.components.HologramStageMode
import com.truong.findmycar.ui.components.ScooterHologramStage
import com.truong.findmycar.ui.theme.BentoCardBorder
import com.truong.findmycar.ui.theme.BentoCardSurface
import com.truong.findmycar.ui.theme.MintAccent
import com.truong.findmycar.util.GeoMath
import com.truong.findmycar.util.PhotoUtils
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindCarScreen(
    viewModel: FindCarViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val guidance = uiState.guidance
    val spot = uiState.spot
    val context = LocalContext.current

    var showNoteSheet by remember { mutableStateOf(false) }

    // Gently open note/photo sheet automatically when entering VERY_CLOSE (< 8m)
    var autoOpenedCloseSheet by remember { mutableStateOf(false) }
    LaunchedEffect(guidance.phase) {
        if (guidance.phase == GuidancePhase.VERY_CLOSE && !autoOpenedCloseSheet) {
            autoOpenedCloseSheet = true
            if (spot != null && (spot.note.isNotBlank() || spot.photoUri != null)) {
                showNoteSheet = true
            }
        }
    }

    var photoBitmap by remember(spot?.photoUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(spot?.photoUri) {
        photoBitmap = spot?.photoUri?.let { uri ->
            PhotoUtils.loadSampledBitmap(context, uri, maxDimensionPx = 1280)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MintAccent.copy(alpha = 0.10f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height * 0.88f),
                            radius = size.width * 0.85f
                        ),
                        radius = size.width * 0.85f,
                        center = Offset(size.width / 2f, size.height * 0.88f)
                    )
                }
                .padding(innerPadding)
        ) {
            val isCompactHeight = maxHeight < 450.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Top Header (wrapContentHeight)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        onClick = onClose,
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoCardBorder),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.find_close_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    guidance.floorDeltaToTarget?.let { delta ->
                        val floorText = when {
                            delta > 0 -> stringResource(R.string.find_floor_up, delta)
                            delta < 0 -> stringResource(R.string.find_floor_down, abs(delta))
                            else -> stringResource(R.string.find_floor_same)
                        }
                        FloorBadge(label = floorText)
                    }
                }

                // 2. Flexible Compass Middle Zone (Modifier.weight(1f))
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (guidance.phase == GuidancePhase.NO_SIGNAL_FALLBACK) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.find_no_signal_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = stringResource(R.string.find_no_signal_desc),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        val compassSize = minOf(maxWidth * 0.84f, maxHeight * 0.90f)
                            .coerceIn(140.dp, 310.dp)

                        val distIntForA11y = guidance.distanceMeters?.roundToInt() ?: 0
                        val dirForA11y = when {
                            guidance.phase == GuidancePhase.VERY_CLOSE -> stringResource(R.string.find_status_very_close)
                            abs(guidance.relativeArrowDeg) < 15f -> stringResource(R.string.find_status_straight)
                            guidance.relativeArrowDeg in 15f..150f -> stringResource(R.string.find_status_right)
                            guidance.relativeArrowDeg in -150f..-15f -> stringResource(R.string.find_status_left)
                            else -> stringResource(R.string.find_status_turn_around)
                        }
                        val floorForA11y = guidance.floorDeltaToTarget?.let { delta ->
                            when {
                                delta > 0 -> stringResource(R.string.find_floor_up, delta)
                                delta < 0 -> stringResource(R.string.find_floor_down, abs(delta))
                                else -> stringResource(R.string.find_floor_same)
                            }
                        }
                        val compassA11y = if (floorForA11y != null) {
                            stringResource(R.string.a11y_compass_with_floor, distIntForA11y, dirForA11y, floorForA11y)
                        } else {
                            stringResource(R.string.a11y_compass_summary, distIntForA11y, dirForA11y)
                        }

                        val isVeryClose = guidance.phase == GuidancePhase.VERY_CLOSE ||
                            (guidance.distanceMeters != null && guidance.distanceMeters < 10f)
                        ScooterHologramStage(
                            mode = HologramStageMode.GUIDING_TO_VEHICLE,
                            vehicleType = uiState.vehicleType,
                            relativeArrowDeg = guidance.relativeArrowDeg,
                            userHeadingDeg = uiState.userHeadingDeg,
                            isVeryClose = isVeryClose,
                            isLowAccuracy = guidance.isCompassLowAccuracy,
                            modifier = Modifier
                                .size(compassSize)
                                .semantics { contentDescription = compassA11y }
                        )
                    }
                }

                // 3. Unified Frosted-Glass Bottom Dock Card (28.dp top corners)
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 28.dp,
                        topEnd = 28.dp,
                        bottomStart = 24.dp,
                        bottomEnd = 24.dp
                    ),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = if (isCompactHeight) 14.dp else 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (guidance.phase != GuidancePhase.NO_SIGNAL_FALLBACK) {
                            val distInt = guidance.distanceMeters?.roundToInt()
                            val statusText = when {
                                guidance.isCompassLowAccuracy -> stringResource(R.string.find_status_calibrate)
                                guidance.hasWeakGpsSignal -> stringResource(R.string.find_status_weak_signal)
                                guidance.phase == GuidancePhase.VERY_CLOSE -> stringResource(R.string.find_status_very_close)
                                abs(guidance.relativeArrowDeg) < 15f -> stringResource(R.string.find_status_straight)
                                guidance.relativeArrowDeg in 15f..150f -> stringResource(R.string.find_status_right)
                                guidance.relativeArrowDeg in -150f..-15f -> stringResource(R.string.find_status_left)
                                else -> stringResource(R.string.find_status_turn_around)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (guidance.phase == GuidancePhase.VERY_CLOSE) {
                                    Text(
                                        text = stringResource(R.string.find_status_very_close),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MintAccent
                                    )
                                } else if (distInt != null) {
                                    Row(
                                        verticalAlignment = Alignment.Bottom,
                                        horizontalArrangement = Arrangement.Start
                                    ) {
                                        AnimatedContent(
                                            targetState = distInt,
                                            transitionSpec = {
                                                (slideInVertically { it / 2 } + fadeIn()) togetherWith
                                                    (slideOutVertically { -it / 2 } + fadeOut())
                                            },
                                            label = "rolling_distance"
                                        ) { value ->
                                            Text(
                                                text = value.toString(),
                                                fontSize = if (isCompactHeight) 40.sp else 52.sp,
                                                fontWeight = FontWeight.Light,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "m",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Light,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }
                                }

                                if (guidance.phase != GuidancePhase.VERY_CLOSE ||
                                    guidance.isCompassLowAccuracy ||
                                    guidance.hasWeakGpsSignal
                                ) {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (guidance.isCompassLowAccuracy || guidance.hasWeakGpsSignal) {
                                            com.truong.findmycar.ui.theme.WarningAmber
                                        } else {
                                            MintAccent
                                        }
                                    )
                                }
                            }

                            if (guidance.canEnterPhase2Manually && !isCompactHeight) {
                                OutlinedButton(
                                    onClick = { viewModel.enterPhase2Manually() },
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, MintAccent.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.find_at_anchor_button),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MintAccent
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showNoteSheet = true },
                                shape = CircleShape,
                                border = BorderStroke(1.dp, BentoCardBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.find_notes_photos_button),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Button(
                                onClick = { viewModel.markCarFound(onClose) },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MintAccent,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.find_found_button),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNoteSheet && spot != null) {
        ModalBottomSheet(
            onDismissRequest = { showNoteSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.find_notes_photos_button),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = if (spot.note.isNotBlank()) spot.note else stringResource(R.string.find_sheet_no_note),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (spot.note.isNotBlank()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                photoBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.detail_photo_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f)
                            .clip(RoundedCornerShape(20.dp))
                    )
                }

                if (spot.lat != null && spot.lon != null) {
                    OutlinedButton(
                        onClick = {
                            val geoUri = Uri.parse(GeoMath.buildGeoUri(spot.lat, spot.lon))
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                            }.onFailure {
                                if (it is ActivityNotFoundException) Unit
                            }
                        },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.detail_open_map),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
