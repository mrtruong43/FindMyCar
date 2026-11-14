package com.truong.findmycar.ui.debug

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.truong.findmycar.R
import com.truong.findmycar.tracking.HeadingMode
import com.truong.findmycar.tracking.TrackingStateHolder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit
) {
    val isTracking by TrackingStateHolder.isTracking.collectAsStateWithLifecycle()
    val telemetry by TrackingStateHolder.telemetry.collectAsStateWithLifecycle()
    val isRecordingCsv by TrackingStateHolder.isRecordingCsv.collectAsStateWithLifecycle()
    val csvRowCount by TrackingStateHolder.csvRowCount.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.debug_title),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryRow(
                        label = stringResource(R.string.debug_status_label),
                        value = if (isTracking) {
                            stringResource(R.string.debug_status_active)
                        } else {
                            stringResource(R.string.debug_status_idle)
                        }
                    )
                    TelemetryRow(
                        label = stringResource(R.string.debug_steps),
                        value = telemetry.stepCount.toString()
                    )
                    TelemetryRow(
                        label = stringResource(R.string.debug_heading),
                        value = String.format(Locale.US, "%.1f°", telemetry.headingDeg)
                    )
                    TelemetryRow(
                        label = stringResource(R.string.debug_mode),
                        value = if (telemetry.headingMode == HeadingMode.HAND_HELD) {
                            stringResource(R.string.debug_mode_hand)
                        } else {
                            stringResource(R.string.debug_mode_pocket)
                        }
                    )
                    TelemetryRow(
                        label = stringResource(R.string.debug_pca_ratio),
                        value = String.format(Locale.US, "%.2f", telemetry.pcaRatio)
                    )
                    TelemetryRow(
                        label = stringResource(R.string.debug_pressure),
                        value = telemetry.pressureHpa?.let {
                            String.format(Locale.US, "%.2f hPa", it)
                        } ?: "—"
                    )
                    TelemetryRow(
                        label = stringResource(R.string.debug_rel_height),
                        value = String.format(Locale.US, "%.2f m", telemetry.relHeightM)
                    )
                    TelemetryRow(
                        label = stringResource(R.string.debug_floor_delta),
                        value = telemetry.floorDelta.toString()
                    )
                    TelemetryRow(
                        label = stringResource(R.string.debug_coords),
                        value = String.format(Locale.US, "(%.2f, %.2f) m", telemetry.x, telemetry.y)
                    )
                }
            }

            OutlinedButton(
                onClick = { TrackingStateHolder.setCsvRecording(!isRecordingCsv) },
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    if (isRecordingCsv) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (isRecordingCsv) {
                        stringResource(R.string.debug_csv_stop)
                    } else {
                        stringResource(R.string.debug_csv_start)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isRecordingCsv) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            Button(
                onClick = {
                    val content = TrackingStateHolder.buildCsvContent()
                    if (content == null) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.debug_csv_empty)
                            )
                        }
                        return@Button
                    }

                    val debugDir = File(context.cacheDir, "debug").apply { mkdirs() }
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val csvFile = File(debugDir, "findmycar_log_$ts.csv")
                    csvFile.writeText(content)

                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        csvFile
                    )
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(
                            sendIntent,
                            context.getString(R.string.debug_csv_share_title)
                        )
                    )
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = stringResource(R.string.debug_csv_export, csvRowCount),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
