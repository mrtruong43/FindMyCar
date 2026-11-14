package com.truong.findmycar.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.truong.findmycar.R
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.tracking.TrackingService
import com.truong.findmycar.ui.components.FloorBadge
import com.truong.findmycar.ui.components.formatElapsedTime
import com.truong.findmycar.util.Permissions
import com.truong.findmycar.util.PhotoUtils
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDetail: (Long) -> Unit,
    onOpenFind: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Save proceeds gracefully even if location permission is denied.
        viewModel.saveManualSpot()
    }

    val onRequestSave: () -> Unit = {
        if (!uiState.isSaving) {
            if (Permissions.hasLocationPermission(context)) {
                viewModel.saveManualSpot()
            } else {
                permissionLauncher.launch(Permissions.LOCATION_PERMISSIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.saveEvents.collect { result ->
            if (result.skippedDuplicate) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.home_spot_duplicate_toast)
                )
                return@collect
            }
            if (Permissions.hasForegroundLocation(context)) {
                TrackingService.start(context, result.spotId)
            }
            val msg = if (result.hasLocation) {
                context.getString(R.string.home_spot_saved_toast)
            } else {
                context.getString(R.string.home_spot_saved_no_gps_toast)
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            HomeTopBar(
                onOpenHistory = onOpenHistory,
                onOpenSettings = onOpenSettings
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            val isCompactHeight = maxHeight < 520.dp
            val horizontalPadding = if (maxWidth < 360.dp) 16.dp else 24.dp

            AnimatedContent(
                targetState = uiState.activeSpot,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "home_state_transition"
            ) { spot ->
                if (spot == null) {
                    EmptyHomeContent(
                        isSaving = uiState.isSaving,
                        isCompactHeight = isCompactHeight,
                        circleDiameter = minOf(maxWidth * 0.46f, maxHeight * 0.34f).coerceIn(132.dp, 196.dp),
                        horizontalPadding = horizontalPadding,
                        onSaveClick = onRequestSave
                    )
                } else {
                    ActiveHomeContent(
                        spot = spot,
                        isSaving = uiState.isSaving,
                        isTracking = uiState.isTracking,
                        trackingElapsedSeconds = uiState.trackingElapsedSeconds,
                        stepCount = uiState.stepCount,
                        isCompactHeight = isCompactHeight,
                        horizontalPadding = horizontalPadding,
                        onCardClick = { onOpenDetail(spot.id) },
                        onFindClick = { onOpenFind(spot.id) },
                        onOverwriteSaveClick = onRequestSave,
                        onAnchorReachedClick = {
                            TrackingService.finalizeAnchor(context)
                        },
                        onStartTrackingTestClick = {
                            if (Permissions.hasForegroundLocation(context)) {
                                TrackingService.start(context, spot.id)
                            } else {
                                permissionLauncher.launch(Permissions.FOREGROUND_LOCATION_PERMISSIONS)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenHistory,
            modifier = Modifier.size(56.dp)
        ) {
            // Minimal thin-stroke clock/history vector via Canvas-like simple icon
            HistoryThinIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.home_settings_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryThinIcon(tint: androidx.compose.ui.graphics.Color) {
    val cd = stringResource(R.string.home_history_cd)
    Box(
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color = tint, style = stroke, radius = size.minDimension * 0.42f)
            drawLine(
                color = tint,
                start = center,
                end = center.copy(y = center.y - size.minDimension * 0.22f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = tint,
                start = center,
                end = center.copy(x = center.x + size.minDimension * 0.16f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        // Accessibility label via invisible semantic text if needed
        Spacer(modifier = Modifier.size(0.dp))
    }
}

@Composable
private fun EmptyHomeContent(
    isSaving: Boolean,
    isCompactHeight: Boolean,
    circleDiameter: Dp,
    horizontalPadding: Dp,
    onSaveClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isCompactHeight) Arrangement.Center else Arrangement.Bottom
    ) {
        if (!isCompactHeight) {
            Spacer(modifier = Modifier.weight(1f))
        }

        SaveCarCircleButton(
            diameter = circleDiameter,
            isSaving = isSaving,
            onClick = onSaveClick
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.home_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(if (isCompactHeight) 16.dp else 56.dp))
    }
}

@Composable
private fun SaveCarCircleButton(
    diameter: Dp,
    isSaving: Boolean,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(surface)
            .clickable(enabled = !isSaving, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = accent.copy(alpha = 0.22f),
                radius = size.minDimension / 2f - 4.dp.toPx(),
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = accent,
                radius = size.minDimension / 2f - 12.dp.toPx(),
                style = Stroke(width = 1.8.dp.toPx())
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = accent,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.home_saving_car),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
            } else {
                Text(
                    text = stringResource(R.string.home_save_car),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ActiveHomeContent(
    spot: ParkingSpotEntity,
    isSaving: Boolean,
    isTracking: Boolean,
    trackingElapsedSeconds: Long,
    stepCount: Int,
    isCompactHeight: Boolean,
    horizontalPadding: Dp,
    onCardClick: () -> Unit,
    onFindClick: () -> Unit,
    onOverwriteSaveClick: () -> Unit,
    onAnchorReachedClick: () -> Unit,
    onStartTrackingTestClick: () -> Unit
) {
    val context = LocalContext.current
    var nowMs by remember(spot.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(spot.id, spot.savedAt) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    var photoBitmap by remember(spot.photoUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(spot.photoUri) {
        photoBitmap = spot.photoUri?.let { uri ->
            PhotoUtils.loadSampledBitmap(context, uri, maxDimensionPx = 512)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!isCompactHeight) {
            Spacer(modifier = Modifier.weight(0.45f))
        }

        // Central status card (28.dp corner radius)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .clickable(onClick = onCardClick),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.home_your_car),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (spot.pressureHpa != null) {
                        FloorBadge(label = stringResource(R.string.home_pressure_saved))
                    }
                }

                Text(
                    text = formatElapsedTime(context, spot.savedAt, nowMs),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = if (spot.lat != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (spot.lat != null && spot.lon != null) {
                            stringResource(R.string.home_gps_saved)
                        } else {
                            stringResource(R.string.home_no_gps)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (spot.note.isNotBlank() || photoBitmap != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        photoBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.detail_photo_cd),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        }
                        if (spot.note.isNotBlank()) {
                            Text(
                                text = spot.note,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_detail_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        TrackingStatusCard(
            isTracking = isTracking,
            elapsedSeconds = trackingElapsedSeconds,
            stepCount = stepCount,
            anchorSet = spot.anchorSet,
            onAnchorReachedClick = onAnchorReachedClick,
            onStartTrackingTestClick = onStartTrackingTestClick
        )

        if (!isCompactHeight) {
            Spacer(modifier = Modifier.weight(0.55f))
        } else {
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Prominent "Tìm xe" CTA in thumb-reachable lower half
        Button(
            onClick = onFindClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text(
                text = stringResource(R.string.home_find_car),
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Compact secondary "Lưu xe" button to overwrite with a new spot
        OutlinedButton(
            onClick = onOverwriteSaveClick,
            enabled = !isSaving,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.height(56.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_saving_car),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.home_save_car),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TrackingStatusCard(
    isTracking: Boolean,
    elapsedSeconds: Long,
    stepCount: Int,
    anchorSet: Boolean,
    onAnchorReachedClick: () -> Unit,
    onStartTrackingTestClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (isTracking) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outline
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isTracking) {
                Text(
                    text = stringResource(R.string.tracking_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(
                        R.string.tracking_card_steps_elapsed,
                        stepCount,
                        TrackingService.formatDurationMmSs(elapsedSeconds)
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedButton(
                    onClick = onAnchorReachedClick,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tracking_anchor_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (anchorSet) {
                Text(
                    text = stringResource(R.string.tracking_anchor_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tracking_anchor_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onStartTrackingTestClick,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tracking_start_test),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
