package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import com.muslimedu.attendance.data.local.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val minMatchScore: StateFlow<Float> = settingsRepository.minMatchScore
    val livenessThreshold: StateFlow<Float> = settingsRepository.livenessThreshold

    fun setMinMatchScore(value: Float) = settingsRepository.setMinMatchScore(value)

    fun setLivenessThreshold(value: Float) = settingsRepository.setLivenessThreshold(value)

    fun resetToDefaults() = settingsRepository.resetToDefaults()
}
