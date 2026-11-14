package com.truong.findmycar.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val userHeightM: Float = SettingsStore.DEFAULT_USER_HEIGHT_M,
    val floorHeightM: Float = SettingsStore.DEFAULT_FLOOR_HEIGHT_M,
    val autoDetectEnabled: Boolean = SettingsStore.DEFAULT_AUTO_DETECT_ENABLED,
    val hapticFeedbackEnabled: Boolean = SettingsStore.DEFAULT_HAPTIC_FEEDBACK_ENABLED,
    val onboardingCompleted: Boolean = false
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val USER_HEIGHT_M = floatPreferencesKey("user_height_m")
        val FLOOR_HEIGHT_M = floatPreferencesKey("floor_height_m")
        val AUTO_DETECT_ENABLED = booleanPreferencesKey("auto_detect_enabled")
        val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("haptic_feedback_enabled")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            userHeightM = prefs[Keys.USER_HEIGHT_M] ?: DEFAULT_USER_HEIGHT_M,
            floorHeightM = prefs[Keys.FLOOR_HEIGHT_M] ?: DEFAULT_FLOOR_HEIGHT_M,
            autoDetectEnabled = prefs[Keys.AUTO_DETECT_ENABLED] ?: DEFAULT_AUTO_DETECT_ENABLED,
            hapticFeedbackEnabled = prefs[Keys.HAPTIC_FEEDBACK_ENABLED] ?: DEFAULT_HAPTIC_FEEDBACK_ENABLED,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
        )
    }

    suspend fun setUserHeightM(heightM: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.USER_HEIGHT_M] = heightM
        }
    }

    suspend fun setFloorHeightM(heightM: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.FLOOR_HEIGHT_M] = heightM
        }
    }

    suspend fun setAutoDetectEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.AUTO_DETECT_ENABLED] = enabled
        }
    }

    suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.HAPTIC_FEEDBACK_ENABLED] = enabled
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
    }

    companion object {
        const val DEFAULT_USER_HEIGHT_M = 1.65f
        const val DEFAULT_FLOOR_HEIGHT_M = 3.2f
        const val DEFAULT_AUTO_DETECT_ENABLED = true
        const val DEFAULT_HAPTIC_FEEDBACK_ENABLED = true
    }
}
