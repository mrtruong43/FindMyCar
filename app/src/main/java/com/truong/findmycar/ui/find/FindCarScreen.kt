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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.truong.findmycar.R
import com.truong.findmycar.guidance.GuidancePhase
import com.truong.findmycar.ui.components.ArrowCompass
import com.truong.findmycar.ui.components.FloorBadge
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
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val compassSize = minOf(maxWidth * 0.70f, maxHeight * 0.42f).coerceIn(180.dp, 280.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Bar: Close (X) on corner + centered FloorBadge pill
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.find_close_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

                Spacer(modifier = Modifier.weight(0.6f))

                if (guidance.phase == GuidancePhase.NO_SIGNAL_FALLBACK) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
                    ArrowCompass(
                        relativeArrowDeg = guidance.relativeArrowDeg,
                        isVeryClose = guidance.phase == GuidancePhase.VERY_CLOSE,
                        isLowAccuracy = guidance.isCompassLowAccuracy,
                        modifier = Modifier.size(compassSize)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Distance readout with rolling digit animation
                    val distInt = guidance.distanceMeters?.roundToInt()
                    if (guidance.phase == GuidancePhase.VERY_CLOSE) {
                        Text(
                            text = stringResource(R.string.find_status_very_close),
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (distInt != null) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.Center
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
                                    fontSize = 64.sp,
                                    fontWeight = FontWeight.Thin,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "m",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Light,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val statusText = when {
                        guidance.isCompassLowAccuracy -> stringResource(R.string.find_status_calibrate)
                        guidance.hasWeakGpsSignal -> stringResource(R.string.find_status_weak_signal)
                        guidance.phase == GuidancePhase.VERY_CLOSE -> ""
                        abs(guidance.relativeArrowDeg) < 15f -> stringResource(R.string.find_status_straight)
                        guidance.relativeArrowDeg in 15f..150f -> stringResource(R.string.find_status_right)
                        guidance.relativeArrowDeg in -150f..-15f -> stringResource(R.string.find_status_left)
                        else -> stringResource(R.string.find_status_turn_around)
                    }
                    if (statusText.isNotEmpty()) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (guidance.canEnterPhase2Manually) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { viewModel.enterPhase2Manually() },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.find_at_anchor_button),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bottom thumb-zone frosted-glass pill buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        onClick = { showNoteSheet = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.find_notes_photos_button),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.markCarFound(onClose) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.find_found_button),
                            style = MaterialTheme.typography.labelLarge
                        )
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
