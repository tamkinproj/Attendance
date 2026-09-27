package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.security.AuditLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin wrapper around [DeviceSettings.kioskModeEnabled] - see
 * [com.muslimedu.attendance.ui.screens.admin.KioskModeScreen] for the
 * screen this backs, and AppRoot for where the resulting state actually
 * changes what's on screen (it reads the same [DeviceSettings] flow
 * directly, rather than through this ViewModel, since it needs it before
 * any screen-specific ViewModel would exist).
 */
@HiltViewModel
class KioskModeViewModel @Inject constructor(
    private val deviceSettings: DeviceSettings,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    val kioskModeEnabled: StateFlow<Boolean> = deviceSettings.kioskModeEnabled

    fun setKioskModeEnabled(enabled: Boolean) {
        deviceSettings.setKioskModeEnabled(enabled)
        viewModelScope.launch {
            auditLogger.log(action = AuditLogger.ACTION_KIOSK_MODE_SET, details = if (enabled) "on" else "off")
        }
    }
}
