package com.truong.findmycar.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.truong.findmycar.R
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.detect.ParkingEnvironmentMode
import com.truong.findmycar.detect.ParkingEnvironmentResolver
import com.truong.findmycar.tracking.TrackingService
import com.truong.findmycar.ui.components.EditAnchorBottomSheet
import com.truong.findmycar.ui.components.FloorBadge
import com.truong.findmycar.ui.components.formatElapsedTime
import com.truong.findmycar.ui.theme.BentoCardBorder
import com.truong.findmycar.ui.theme.BentoCardSurface
import com.truong.findmycar.ui.theme.MintAccent
import com.truong.findmycar.ui.theme.WarningAmber
import com.truong.findmycar.util.GeoMath
import com.truong.findmycar.util.Permissions
import com.truong.findmycar.util.PhotoUtils
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showEditAnchorSheet by remember { mutableStateOf(false) }
    var liveGpsAccuracyM by remember { mutableStateOf<Float?>(null) }
    var livePressureHpa by remember { mutableStateOf<Float?>(null) }

    val isEmptyState = uiState.activeSpot == null
    var hasForegroundLocation by remember {
        mutableStateOf(Permissions.hasForegroundLocation(context))
    }
    var hasMissingAutoDetectPerm by remember {
        mutableStateOf(
            !Permissions.hasForegroundLocation(context) || !Permissions.hasActivityRecognition(context)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasForegroundLocation = Permissions.hasForegroundLocation(context)
                hasMissingAutoDetectPerm =
                    !Permissions.hasForegroundLocation(context) || !Permissions.hasActivityRecognition(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Lightweight passive GPS accuracy sampling only while on empty-state Home screen.
    @SuppressLint("MissingPermission")
    DisposableEffect(isEmptyState, hasForegroundLocation) {
        if (!isEmptyState || !hasForegroundLocation) {
            onDispose { }
        } else {
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && loc.hasAccuracy()) {
                    liveGpsAccuracyM = loc.accuracy
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000L)
                .setMinUpdateIntervalMillis(2_500L)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    if (loc.hasAccuracy()) {
                        liveGpsAccuracyM = loc.accuracy
                    }
                }
            }
            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (_: SecurityException) {
            }
            onDispose {
                client.removeLocationUpdates(callback)
            }
        }
    }

    // Lightweight barometer readout only while on empty-state Home screen.
    DisposableEffect(isEmptyState) {
        if (!isEmptyState) {
            onDispose { }
        } else {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val pressureSensor = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
            if (pressureSensor == null) {
                livePressureHpa = null
                onDispose { }
            } else {
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.isNotEmpty()) {
                            livePressureHpa = event.values[0]
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_UI)
                onDispose {
                    sm.unregisterListener(listener)
                }
            }
        }
    }

    LaunchedEffect(uiState.barometerNoticeShown) {
        if (!uiState.barometerNoticeShown) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sm?.getDefaultSensor(Sensor.TYPE_PRESSURE) == null) {
                viewModel.markBarometerNoticeShown()
                snackbarHostState.showSnackbar(
                    context.getString(R.string.home_barometer_missing_notice)
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
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
            if (result.shouldStartTracking && Permissions.hasForegroundLocation(context)) {
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

    LaunchedEffect(Unit) {
        viewModel.anchorActionMessageRes.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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
                            center = Offset(size.width / 2f, size.height * 0.90f),
                            radius = size.width * 0.85f
                        ),
                        radius = size.width * 0.85f,
                        center = Offset(size.width / 2f, size.height * 0.90f)
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Top Header (wrapContentHeight)
                HomeTopHeader(
                    isAutoDetectActive = uiState.autoDetectEnabled && !hasMissingAutoDetectPerm,
                    onOpenHistory = onOpenHistory,
                    onOpenSettings = onOpenSettings
                )

                if (!isCompactHeight && uiState.autoDetectEnabled && hasMissingAutoDetectPerm) {
                    MissingPermissionBanner(
                        onGrantClick = { Permissions.openAppSettings(context) }
                    )
                }

                // 2. Flexible Bento Middle Zone + 3. Bottom Thumb Dock
                AnimatedContent(
                    targetState = uiState.activeSpot,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.weight(1f),
                    label = "home_bento_state"
                ) { spot ->
                    if (spot == null) {
                        EmptyHomeBentoLayout(
                            isCompactHeight = isCompactHeight,
                            isSaving = uiState.isSaving,
                            liveGpsAccuracyM = liveGpsAccuracyM,
                            livePressureHpa = livePressureHpa,
                            autoDetectEnabled = uiState.autoDetectEnabled,
                            latestHistorySpot = uiState.latestHistorySpot,
                            floorHeightM = uiState.floorHeightM,
                            selectedEnvironmentMode = uiState.selectedEnvironmentMode,
                            onEnvironmentModeSelected = viewModel::selectEnvironmentMode,
                            onSaveClick = onRequestSave,
                            onOpenHistory = onOpenHistory
                        )
                    } else {
                        ActiveHomeBentoLayout(
                            spot = spot,
                            isCompactHeight = isCompactHeight,
                            isTracking = uiState.isTracking,
                            trackingElapsedSeconds = uiState.trackingElapsedSeconds,
                            stepCount = uiState.stepCount,
                            floorHeightM = uiState.floorHeightM,
                            onCardClick = { onOpenDetail(spot.id) },
                            onFindClick = { onOpenFind(spot.id) },
                            onMarkFoundClick = {
                                if (uiState.isTracking) {
                                    TrackingService.finalizeAnchor(context)
                                }
                                viewModel.markActiveSpotFound(spot.id)
                            },
                            onAnchorReachedClick = { TrackingService.finalizeAnchor(context) },
                            onEditAnchorClick = { showEditAnchorSheet = true }
                        )
                    }
                }
            }
        }
    }

    val activeSpot = uiState.activeSpot
    if (showEditAnchorSheet && activeSpot != null) {
        EditAnchorBottomSheet(
            onDismiss = { showEditAnchorSheet = false },
            onUpdateAnchorHere = { viewModel.updateAnchorHere(activeSpot) },
            onClearAnchor = { viewModel.clearAnchor(activeSpot.id) },
            onResumeAppendFootprint = {
                if (Permissions.hasForegroundLocation(context)) {
                    TrackingService.start(context, activeSpot.id, appendMode = true)
                } else {
                    permissionLauncher.launch(Permissions.FOREGROUND_LOCATION_PERMISSIONS)
                }
            }
        )
    }
}

@Composable
private fun HomeTopHeader(
    isAutoDetectActive: Boolean,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (isAutoDetectActive) MintAccent else WarningAmber)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = onOpenHistory,
                shape = CircleShape,
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoCardBorder),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    HistoryThinIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                onClick = onOpenSettings,
                shape = CircleShape,
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoCardBorder),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.home_settings_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryThinIcon(tint: Color) {
    val cd = stringResource(R.string.home_history_cd)
    Box(
        modifier = Modifier
            .size(22.dp)
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
    }
}

@Composable
private fun MissingPermissionBanner(onGrantClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_perm_banner_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = WarningAmber
                )
                Text(
                    text = stringResource(R.string.home_perm_banner_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onGrantClick,
                shape = CircleShape,
                border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.7f)),
                modifier = Modifier.height(56.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_perm_banner_action),
                    style = MaterialTheme.typography.labelMedium,
                    color = WarningAmber
                )
            }
        }
    }
}

@Composable
private fun EmptyHomeBentoLayout(
    isCompactHeight: Boolean,
    isSaving: Boolean,
    liveGpsAccuracyM: Float?,
    livePressureHpa: Float?,
    autoDetectEnabled: Boolean,
    latestHistorySpot: ParkingSpotEntity?,
    floorHeightM: Float,
    selectedEnvironmentMode: ParkingEnvironmentMode,
    onEnvironmentModeSelected: (ParkingEnvironmentMode) -> Unit,
    onSaveClick: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val isStrongGps = liveGpsAccuracyM != null && liveGpsAccuracyM <= 20f

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isCompactHeight) {
            // Compact Foldable Cover / Split-Screen Guard: only primary metric + CTA
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.home_gps_card_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (liveGpsAccuracyM != null) {
                                    "${liveGpsAccuracyM.roundToInt()}m"
                                } else {
                                    stringResource(R.string.home_gps_lost)
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isStrongGps) MintAccent else WarningAmber
                            )
                        }
                        Text(
                            text = if (isStrongGps) {
                                stringResource(R.string.home_gps_outdoor_ready)
                            } else {
                                stringResource(R.string.home_gps_underground_ready)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            // Flexible Bento Middle Zone (weight(1f))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Bento Row 1: 2 equal-width cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GpsReadinessBentoCard(
                        gpsAccuracyM = liveGpsAccuracyM,
                        modifier = Modifier.weight(1f)
                    )
                    SensorsBentoCard(
                        pressureHpa = livePressureHpa,
                        autoDetectEnabled = autoDetectEnabled,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Bento Card 2: Recent Spot / Quick Context Card filling remaining middle height
                RecentOrReadinessBentoCard(
                    latestHistorySpot = latestHistorySpot,
                    floorHeightM = floorHeightM,
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }

        // 3. Bottom Thumb Dock
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ParkingModeSegmentedSelector(
                selectedMode = selectedEnvironmentMode,
                onModeSelected = onEnvironmentModeSelected
            )
            SaveCarPillCta(
                isSaving = isSaving,
                heightDp = if (isCompactHeight) 58 else 72,
                onClick = onSaveClick
            )
        }
    }
}

@Composable
private fun GpsReadinessBentoCard(
    gpsAccuracyM: Float?,
    modifier: Modifier = Modifier
) {
    val isStrongGps = gpsAccuracyM != null && gpsAccuracyM <= 20f
    val statusColor = if (isStrongGps) MintAccent else WarningAmber
    val infiniteTransition = rememberInfiniteTransition(label = "gps_radar_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoCardBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_gps_card_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Canvas(modifier = Modifier.size(18.dp)) {
                    drawCircle(
                        color = statusColor.copy(alpha = pulseAlpha * 0.45f),
                        radius = size.minDimension / 2f
                    )
                    drawCircle(
                        color = statusColor,
                        radius = size.minDimension * 0.24f
                    )
                }
            }

            Text(
                text = if (gpsAccuracyM != null) {
                    "${gpsAccuracyM.roundToInt()}m"
                } else {
                    stringResource(R.string.home_gps_lost)
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )

            Text(
                text = if (isStrongGps) {
                    stringResource(R.string.home_gps_outdoor_ready)
                } else {
                    stringResource(R.string.home_gps_underground_ready)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SensorsBentoCard(
    pressureHpa: Float?,
    autoDetectEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoCardBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.home_sensor_card_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (pressureHpa != null) {
                    stringResource(R.string.home_barometer_hpa, pressureHpa)
                } else {
                    stringResource(R.string.home_barometer_na)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Surface(
                shape = CircleShape,
                color = if (autoDetectEnabled) {
                    MintAccent.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Text(
                    text = if (autoDetectEnabled) {
                        stringResource(R.string.home_auto_detect_on)
                    } else {
                        stringResource(R.string.home_auto_detect_off)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (autoDetectEnabled) MintAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun RecentOrReadinessBentoCard(
    latestHistorySpot: ParkingSpotEntity?,
    floorHeightM: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoCardBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (latestHistorySpot != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.home_recent_spot_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    EnvironmentBadgePill(spot = latestHistorySpot)
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formatElapsedTime(context, latestHistorySpot.savedAt, System.currentTimeMillis()),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MintAccent
                    )
                    val floorDeltaText = latestHistorySpot.anchorRelHeightM?.let { relH ->
                        val delta = GeoMath.estimateFloorDelta(-relH, floorHeightM)
                        when {
                            delta > 0 -> stringResource(R.string.home_floor_delta_up, delta)
                            delta < 0 -> stringResource(R.string.home_floor_delta_down, abs(delta))
                            else -> stringResource(R.string.home_floor_delta_same)
                        }
                    }
                    if (floorDeltaText != null) {
                        FloorBadge(label = floorDeltaText)
                    }
                    if (latestHistorySpot.note.isNotBlank()) {
                        Text(
                            text = latestHistorySpot.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.home_history_cd),
                    style = MaterialTheme.typography.labelLarge,
                    color = MintAccent
                )
            } else {
                Text(
                    text = stringResource(R.string.home_quick_readiness_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.home_quick_step_1),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.home_quick_step_2),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.home_quick_step_3),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun ParkingModeSegmentedSelector(
    selectedMode: ParkingEnvironmentMode,
    onModeSelected: (ParkingEnvironmentMode) -> Unit
) {
    val options = listOf(
        ParkingEnvironmentMode.AUTO to stringResource(R.string.home_mode_auto),
        ParkingEnvironmentMode.UNDERGROUND to stringResource(R.string.home_mode_underground),
        ParkingEnvironmentMode.OUTDOOR to stringResource(R.string.home_mode_outdoor)
    )

    Surface(
        shape = CircleShape,
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { (mode, label) ->
                val selected = mode == selectedMode
                val bgColor by animateColorAsState(
                    targetValue = if (selected) MintAccent else Color.Transparent,
                    animationSpec = tween(durationMillis = 200),
                    label = "mode_bg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 200),
                    label = "mode_fg"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .clickable { onModeSelected(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveCarPillCta(
    isSaving: Boolean,
    heightDp: Int,
    onClick: () -> Unit
) {
    val glowBorder = Brush.horizontalGradient(
        colors = listOf(
            MintAccent.copy(alpha = 0.85f),
            MintAccent.copy(alpha = 0.45f),
            MintAccent.copy(alpha = 0.85f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        MintAccent.copy(alpha = 0.22f),
                        MintAccent.copy(alpha = 0.14f)
                    )
                )
            )
            .border(width = 1.5.dp, brush = glowBorder, shape = CircleShape)
            .clickable(enabled = !isSaving, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSaving) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MintAccent,
                    strokeWidth = 2.5.dp
                )
                Text(
                    text = stringResource(R.string.home_saving_car),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MintAccent
                )
            }
        } else {
            Text(
                text = stringResource(R.string.home_save_car_cta),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MintAccent
            )
        }
    }
}

@Composable
private fun ActiveHomeBentoLayout(
    spot: ParkingSpotEntity,
    isCompactHeight: Boolean,
    isTracking: Boolean,
    trackingElapsedSeconds: Long,
    stepCount: Int,
    floorHeightM: Float,
    onCardClick: () -> Unit,
    onFindClick: () -> Unit,
    onMarkFoundClick: () -> Unit,
    onAnchorReachedClick: () -> Unit,
    onEditAnchorClick: () -> Unit
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
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero Bento Card (Modifier.weight(1f))
        Surface(
            onClick = onCardClick,
            shape = RoundedCornerShape(24.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoCardBorder),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.home_your_car),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        EnvironmentBadgePill(spot = spot)
                    }

                    Text(
                        text = formatElapsedTime(context, spot.savedAt, nowMs),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (!isCompactHeight) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (stepCount > 0 || isTracking) {
                                FloorBadge(label = stringResource(R.string.home_steps_count, stepCount))
                            }
                            spot.anchorRelHeightM?.let { relH ->
                                val delta = GeoMath.estimateFloorDelta(-relH, floorHeightM)
                                val floorText = when {
                                    delta > 0 -> stringResource(R.string.home_floor_delta_up, delta)
                                    delta < 0 -> stringResource(R.string.home_floor_delta_down, abs(delta))
                                    else -> stringResource(R.string.home_floor_delta_same)
                                }
                                FloorBadge(label = floorText)
                            }
                        }
                    }
                }

                if (!isCompactHeight && (spot.note.isNotBlank() || photoBitmap != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isTracking) {
                        Text(
                            text = stringResource(
                                R.string.tracking_card_steps_elapsed,
                                stepCount,
                                TrackingService.formatDurationMmSs(trackingElapsedSeconds)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MintAccent
                        )
                        OutlinedButton(
                            onClick = onAnchorReachedClick,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MintAccent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tracking_anchor_button),
                                style = MaterialTheme.typography.labelLarge,
                                color = MintAccent
                            )
                        }
                    } else if (spot.anchorSet && !isCompactHeight) {
                        OutlinedButton(
                            onClick = onEditAnchorClick,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, BentoCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.anchor_edit_button),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = MintAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.home_detail_action),
                            style = MaterialTheme.typography.labelLarge,
                            color = MintAccent
                        )
                    }
                }
            }
        }

        // Bottom Thumb Dock: Tìm xe (64.dp) + Đã lấy xe
        val dockHeight = if (isCompactHeight) 56.dp else 64.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onMarkFoundClick,
                shape = CircleShape,
                border = BorderStroke(1.dp, BentoCardBorder),
                modifier = Modifier
                    .weight(0.38f)
                    .height(dockHeight)
            ) {
                Text(
                    text = stringResource(R.string.home_mark_found),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onFindClick,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MintAccent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .weight(0.62f)
                    .height(dockHeight)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.home_find_car),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EnvironmentBadgePill(spot: ParkingSpotEntity) {
    val resolved = ParkingEnvironmentResolver.resolve(
        mode = ParkingEnvironmentResolver.fromStorageName(spot.environmentMode),
        accuracyM = spot.accuracyM
    )
    val isOutdoor = resolved == ParkingEnvironmentMode.OUTDOOR
    val badgeColor = if (isOutdoor) MintAccent else WarningAmber
    val label = if (isOutdoor) {
        stringResource(R.string.home_mode_outdoor)
    } else {
        stringResource(R.string.home_mode_underground)
    }

    Surface(
        shape = CircleShape,
        color = badgeColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
