package com.truong.findmycar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.truong.findmycar.R
import com.truong.findmycar.ui.theme.WarningAmber
import com.truong.findmycar.util.Permissions

@Composable
fun BatteryOptimizationCard(
    isIgnoringBattery: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (isIgnoringBattery) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                WarningAmber.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.battery_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = if (isIgnoringBattery) {
                    stringResource(R.string.battery_status_ok)
                } else {
                    stringResource(R.string.battery_status_need)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isIgnoringBattery) {
                    MaterialTheme.colorScheme.primary
                } else {
                    WarningAmber
                }
            )

            Text(
                text = stringResource(R.string.battery_oneui_steps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isIgnoringBattery) {
                Button(
                    onClick = { Permissions.requestIgnoreBatteryOptimizations(context) },
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
                        text = stringResource(R.string.battery_action_button),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
