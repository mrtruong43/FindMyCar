package com.truong.findmycar.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.truong.findmycar.R
import com.truong.findmycar.detect.ActivityTransitionManager
import com.truong.findmycar.ui.components.BatteryOptimizationCard
import com.truong.findmycar.util.Permissions

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentStep by remember { mutableIntStateOf(0) }

    var hasFgLocation by remember { mutableStateOf(Permissions.hasForegroundLocation(context)) }
    var hasBgLocation by remember { mutableStateOf(Permissions.hasBackgroundLocation(context)) }
    var hasActivity by remember { mutableStateOf(Permissions.hasActivityRecognition(context)) }
    var hasNotif by remember { mutableStateOf(Permissions.hasPostNotifications(context)) }
    var isIgnoringBattery by remember { mutableStateOf(Permissions.isIgnoringBatteryOptimizations(context)) }

    fun refreshStates() {
        hasFgLocation = Permissions.hasForegroundLocation(context)
        hasBgLocation = Permissions.hasBackgroundLocation(context)
        hasActivity = Permissions.hasActivityRecognition(context)
        hasNotif = Permissions.hasPostNotifications(context)
        isIgnoringBattery = Permissions.isIgnoringBatteryOptimizations(context)
    }

    // Refresh automatically when returning from Android system permission/battery settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fgLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStates() }

    val singlePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshStates()
        if (Permissions.hasActivityRecognition(context)) {
            ActivityTransitionManager.register(context)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepIndicator(total = 3, current = currentStep)
                TextButton(
                    onClick = onComplete,
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "onboarding_step"
            ) { step ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (step) {
                        0 -> ValuePropositionStep()
                        1 -> PermissionsStep(
                            hasFgLocation = hasFgLocation,
                            hasBgLocation = hasBgLocation,
                            hasActivity = hasActivity,
                            hasNotif = hasNotif,
                            onRequestFgLocation = {
                                fgLocationLauncher.launch(Permissions.FOREGROUND_LOCATION_PERMISSIONS)
                            },
                            onRequestBgLocation = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    singlePermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
                            },
                            onRequestActivity = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    singlePermLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                }
                            },
                            onRequestNotif = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    singlePermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                        else -> SamsungBatteryStep(isIgnoringBattery = isIgnoringBattery)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (currentStep < 2) {
                        currentStep += 1
                    } else {
                        onComplete()
                    }
                },
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
                    text = if (currentStep < 2) {
                        stringResource(R.string.onboarding_next)
                    } else {
                        stringResource(R.string.onboarding_finish)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StepIndicator(total: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (active) 24.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
            )
        }
    }
}

@Composable
private fun ValuePropositionStep() {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.onboarding_step1_title),
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = stringResource(R.string.onboarding_step1_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "• ${stringResource(R.string.onboarding_step1_point1)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "• ${stringResource(R.string.onboarding_step1_point2)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PermissionsStep(
    hasFgLocation: Boolean,
    hasBgLocation: Boolean,
    hasActivity: Boolean,
    hasNotif: Boolean,
    onRequestFgLocation: () -> Unit,
    onRequestBgLocation: () -> Unit,
    onRequestActivity: () -> Unit,
    onRequestNotif: () -> Unit
) {
    Text(
        text = stringResource(R.string.onboarding_step2_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = stringResource(R.string.onboarding_step2_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    PermissionItemCard(
        title = stringResource(R.string.perm_fg_location_title),
        description = stringResource(R.string.perm_fg_location_desc),
        granted = hasFgLocation,
        enabled = true,
        actionLabel = stringResource(R.string.perm_grant_action),
        onAction = onRequestFgLocation
    )

    // Two-step location rule: background location is only enabled after foreground location is granted.
    PermissionItemCard(
        title = stringResource(R.string.perm_bg_location_title),
        description = stringResource(R.string.perm_bg_location_desc),
        granted = hasBgLocation,
        enabled = hasFgLocation,
        actionLabel = if (hasFgLocation) {
            stringResource(R.string.perm_grant_action)
        } else {
            stringResource(R.string.perm_need_fg_first)
        },
        onAction = onRequestBgLocation
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PermissionItemCard(
            title = stringResource(R.string.perm_activity_title),
            description = stringResource(R.string.perm_activity_desc),
            granted = hasActivity,
            enabled = true,
            actionLabel = stringResource(R.string.perm_grant_action),
            onAction = onRequestActivity
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionItemCard(
            title = stringResource(R.string.perm_notif_title),
            description = stringResource(R.string.perm_notif_desc),
            granted = hasNotif,
            enabled = true,
            actionLabel = stringResource(R.string.perm_grant_action),
            onAction = onRequestNotif
        )
    }
}

@Composable
private fun PermissionItemCard(
    title: String,
    description: String,
    granted: Boolean,
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (granted) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (granted) {
                Text(
                    text = stringResource(R.string.perm_granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                OutlinedButton(
                    onClick = onAction,
                    enabled = enabled,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SamsungBatteryStep(isIgnoringBattery: Boolean) {
    Text(
        text = stringResource(R.string.onboarding_step3_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = stringResource(R.string.onboarding_step3_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    BatteryOptimizationCard(isIgnoringBattery = isIgnoringBattery)
}
