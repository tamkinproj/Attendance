package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.db.dao.AuditLogDao
import com.muslimedu.attendance.data.db.entities.AuditLogEntity
import com.muslimedu.attendance.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuditLogViewModel @Inject constructor(
    private val auditLogDao: AuditLogDao,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<AuditLogEntity>>(emptyList())
    val entries: StateFlow<List<AuditLogEntity>> = _entries.asStateFlow()

    init {
        // Reloads on every session change, not once at construction - same
        // reasoning as the other Phase 1/2 ViewModels: this one is
        // Activity-scoped and outlives a logout.
        viewModelScope.launch {
            sessionManager.currentUser.collect { user ->
                _entries.value = if (user == null) emptyList() else auditLogDao.getRecentForSchool(user.schoolId)
            }
        }
    }

    fun refresh() {
        val schoolId = sessionManager.currentUser.value?.schoolId ?: return
        viewModelScope.launch { _entries.value = auditLogDao.getRecentForSchool(schoolId) }
    }
}
