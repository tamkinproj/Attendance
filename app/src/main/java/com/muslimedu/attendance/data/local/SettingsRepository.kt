package com.muslimedu.attendance.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Admin-adjustable face-verification thresholds. Plain (unencrypted)
 * SharedPreferences is fine here - unlike [com.muslimedu.attendance.security.TokenManager],
 * nothing stored is sensitive, it's just two tuning knobs.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    private val _minMatchScore = MutableStateFlow(prefs.getFloat(KEY_MIN_MATCH_SCORE, DEFAULT_MIN_MATCH_SCORE))
    val minMatchScore: StateFlow<Float> = _minMatchScore.asStateFlow()

    private val _livenessThreshold = MutableStateFlow(prefs.getFloat(KEY_LIVENESS_THRESHOLD, DEFAULT_LIVENESS_THRESHOLD))
    val livenessThreshold: StateFlow<Float> = _livenessThreshold.asStateFlow()

    fun setMinMatchScore(value: Float) {
        prefs.edit().putFloat(KEY_MIN_MATCH_SCORE, value).apply()
        _minMatchScore.value = value
    }

    fun setLivenessThreshold(value: Float) {
        prefs.edit().putFloat(KEY_LIVENESS_THRESHOLD, value).apply()
        _livenessThreshold.value = value
    }

    fun resetToDefaults() {
        setMinMatchScore(DEFAULT_MIN_MATCH_SCORE)
        setLivenessThreshold(DEFAULT_LIVENESS_THRESHOLD)
    }

    companion object {
        private const val PREFS_FILE_NAME = "settings_prefs"
        private const val KEY_MIN_MATCH_SCORE = "min_match_score"
        private const val KEY_LIVENESS_THRESHOLD = "liveness_threshold"
        const val DEFAULT_MIN_MATCH_SCORE = 0.85f
        const val DEFAULT_LIVENESS_THRESHOLD = 0.7f
    }
}
