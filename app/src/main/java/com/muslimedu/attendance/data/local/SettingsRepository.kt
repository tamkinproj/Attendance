package com.muslimedu.attendance.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Admin-adjustable face settings: the verification thresholds and whether
 * faces are shared with the school's other gate phones. Plain (unencrypted)
 * SharedPreferences is fine here - unlike [com.muslimedu.attendance.security.TokenManager],
 * nothing stored is sensitive, just tuning knobs and a switch.
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

    private val _shareFaces = MutableStateFlow(prefs.getBoolean(KEY_SHARE_FACES, true))

    /**
     * Share faces registered here with the school's other gate phones, and
     * take theirs, through the school server (FaceSyncManager). On by
     * default - the user asked for it; off keeps every face on this phone only.
     */
    val shareFaces: StateFlow<Boolean> = _shareFaces.asStateFlow()

    fun setShareFaces(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHARE_FACES, value).apply()
        _shareFaces.value = value
    }

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
        // New key with the MobileFaceNet model: a threshold saved for the old
        // landmark matcher means something else on this scale, so drop it.
        private const val KEY_MIN_MATCH_SCORE = "min_match_score_mobilefacenet"
        private const val KEY_LIVENESS_THRESHOLD = "liveness_threshold"
        private const val KEY_SHARE_FACES = "share_faces"
        // MobileFaceNet score (FaceAlignment.matchScore): ~0.5 for different
        // people, 0.9+ for the same person. 0.75 = cosine 0.5, between the two.
        const val DEFAULT_MIN_MATCH_SCORE = 0.75f
        const val DEFAULT_LIVENESS_THRESHOLD = 0.7f
    }
}
