package com.truong.findmycar.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.truong.findmycar.R
import com.truong.findmycar.tracking.StepTracker

@Composable
fun StepCalibrationDialog(
    onDismiss: () -> Unit,
    onSaveCalibratedStepLength: (Float) -> Unit
) {
    val context = LocalContext.current
    var isCounting by remember { mutableStateOf(false) }
    var hasFinishedWalk by remember { mutableStateOf(false) }
    var stepCount by remember { mutableIntStateOf(0) }
    var distanceInput by remember { mutableStateOf("20") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val stepTracker = remember {
        StepTracker(context) {
            stepCount += 1
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stepTracker.stop()
        }
    }

    AlertDialog(
        onDismissRequest = {
            stepTracker.stop()
            onDismiss()
        },
        title = { Text(text = stringResource(R.string.calib_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.calib_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = stringResource(R.string.calib_steps_count, stepCount),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (!isCounting && !hasFinishedWalk) {
                    Button(
                        onClick = {
                            stepCount = 0
                            errorMsg = null
                            val ok = stepTracker.start()
                            if (ok) {
                                isCounting = true
                            } else {
                                errorMsg = context.getString(R.string.calib_no_sensor)
                            }
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
                        Text(text = stringResource(R.string.calib_start))
                    }
                } else if (isCounting) {
                    OutlinedButton(
                        onClick = {
                            stepTracker.stop()
                            isCounting = false
                            hasFinishedWalk = true
                        },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.calib_finish),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (hasFinishedWalk) {
                    OutlinedTextField(
                        value = distanceInput,
                        onValueChange = {
                            distanceInput = it
                            errorMsg = null
                        },
                        label = { Text(text = stringResource(R.string.calib_distance_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                errorMsg?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (hasFinishedWalk) {
                TextButton(
                    onClick = {
                        val dist = distanceInput.replace(',', '.').toFloatOrNull()
                        if (stepCount <= 0 || dist == null || dist <= 0f) {
                            errorMsg = context.getString(R.string.calib_invalid)
                        } else {
                            onSaveCalibratedStepLength(dist / stepCount.toFloat())
                            onDismiss()
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.calib_save),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    stepTracker.stop()
                    onDismiss()
                }
            ) {
                Text(text = stringResource(R.string.common_cancel))
            }
        }
    )
}
