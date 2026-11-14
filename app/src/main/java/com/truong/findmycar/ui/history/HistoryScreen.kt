package com.truong.findmycar.ui.history

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.truong.findmycar.R
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.data.db.SpotSource
import com.truong.findmycar.data.db.SpotStatus
import com.truong.findmycar.ui.components.FloorBadge
import com.truong.findmycar.util.GeoMath
import com.truong.findmycar.util.PhotoUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedSpot by remember { mutableStateOf<ParkingSpotEntity?>(null) }
    var spotToDelete by remember { mutableStateOf<ParkingSpotEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.history_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back_cd)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        if (uiState.spots.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(uiState.spots, key = { it.id }) { spot ->
                    HistorySpotCard(
                        spot = spot,
                        floorHeightM = uiState.floorHeightM,
                        onClick = { selectedSpot = spot },
                        onDeleteClick = { spotToDelete = spot }
                    )
                }
            }
        }
    }

    selectedSpot?.let { spot ->
        var fullBitmap by remember(spot.photoUri) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(spot.photoUri) {
            fullBitmap = spot.photoUri?.let { PhotoUtils.loadSampledBitmap(context, it, 1280) }
        }

        ModalBottomSheet(
            onDismissRequest = { selectedSpot = null },
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
                    text = formatDateTime(spot.savedAt),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                formatFloorText(spot, uiState.floorHeightM)?.let { floorLabel ->
                    FloorBadge(label = floorLabel)
                }

                Text(
                    text = if (spot.note.isNotBlank()) spot.note else stringResource(R.string.find_sheet_no_note),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (spot.note.isNotBlank()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                fullBitmap?.let { bmp ->
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (spot.lat == null || spot.lon == null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.history_no_coords_share)
                                    )
                                }
                            } else {
                                val baseText = context.getString(
                                    R.string.history_share_text_with_coords,
                                    spot.lat,
                                    spot.lon
                                )
                                val shareBody = if (spot.note.isNotBlank()) {
                                    "$baseText\n${spot.note}"
                                } else {
                                    baseText
                                }
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareBody)
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        sendIntent,
                                        context.getString(R.string.history_share_chooser)
                                    )
                                )
                            }
                        },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.history_share_location),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Button(
                        onClick = {
                            if (spot.lat != null && spot.lon != null) {
                                val geoUri = Uri.parse(GeoMath.buildGeoUri(spot.lat, spot.lon))
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                                } catch (_: ActivityNotFoundException) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.detail_no_map_app)
                                        )
                                    }
                                }
                            }
                        },
                        enabled = spot.lat != null && spot.lon != null,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.detail_open_map),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    spotToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { spotToDelete = null },
            title = { Text(text = stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(text = stringResource(R.string.history_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSpot(target)
                        if (selectedSpot?.id == target.id) {
                            selectedSpot = null
                        }
                        spotToDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.common_confirm_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { spotToDelete = null }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun HistorySpotCard(
    spot: ParkingSpotEntity,
    floorHeightM: Float,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    var thumbBitmap by remember(spot.photoUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(spot.photoUri) {
        thumbBitmap = spot.photoUri?.let { PhotoUtils.loadSampledBitmap(context, it, 320) }
    }

    val sourceLabel = if (spot.source == SpotSource.AUTO_ACTIVITY) {
        stringResource(R.string.history_source_auto)
    } else {
        stringResource(R.string.history_source_manual)
    }

    val statusLabel = if (spot.status == SpotStatus.FOUND) {
        stringResource(R.string.history_status_found)
    } else {
        stringResource(R.string.history_status_archived)
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            thumbBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.detail_photo_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatDateTime(spot.savedAt),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "$sourceLabel • $statusLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (spot.status == SpotStatus.FOUND) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                formatFloorText(spot, floorHeightM)?.let { floorLabel ->
                    FloorBadge(label = floorLabel)
                }

                if (spot.note.isNotBlank()) {
                    Text(
                        text = spot.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.history_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun formatFloorText(spot: ParkingSpotEntity, floorHeightM: Float): String? {
    val relH = spot.anchorRelHeightM ?: return null
    val delta = GeoMath.estimateFloorDelta(-relH, floorHeightM)
    return when {
        delta > 0 -> stringResource(R.string.find_floor_up, delta)
        delta < 0 -> stringResource(R.string.find_floor_down, abs(delta))
        else -> stringResource(R.string.find_floor_same)
    }
}

private fun formatDateTime(epochMs: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMs))
}
