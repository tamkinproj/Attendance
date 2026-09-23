package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.dao.AuditLogDao
import com.muslimedu.attendance.data.db.entities.AuditLogEntity
import com.muslimedu.attendance.data.local.DeviceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuditLogViewModel @Inject constructor(
    private val auditLogDao: AuditLogDao,
    private val deviceSettings: DeviceSettings,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<AuditLogEntity>>(emptyList())
    val entries: StateFlow<List<AuditLogEntity>> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            deviceSettings.schoolId.collect { schoolId -> _entries.value = auditLogDao.getRecentForSchool(schoolId) }
        }
    }

    fun refresh() {
        viewModelScope.launch { _entries.value = auditLogDao.getRecentForSchool(deviceSettings.schoolId.value) }
    }
}
