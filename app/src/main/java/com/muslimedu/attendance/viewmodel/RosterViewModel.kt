package com.muslimedu.attendance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muslimedu.attendance.data.remote.dto.TeacherClassDto
import com.muslimedu.attendance.data.repository.RosterRepository
import com.muslimedu.attendance.data.session.ActiveClass
import com.muslimedu.attendance.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RosterUiState {
    data object Loading : RosterUiState()
    data class SelectClass(val classes: List<TeacherClassDto>) : RosterUiState()
    data object Ready : RosterUiState()
    data class Error(val message: String) : RosterUiState()
}

@HiltViewModel
class RosterViewModel @Inject constructor(
    private val rosterRepository: RosterRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RosterUiState>(RosterUiState.Loading)
    val uiState: StateFlow<RosterUiState> = _uiState.asStateFlow()

    init {
        // Re-runs on every session change rather than once at construction.
        // This ViewModel is Activity-scoped and logout doesn't kill the
        // process, so it used to stay parked on Ready with the *previous*
        // teacher's synced class - whoever logged in next scanned against
        // someone else's roster and never got their own class list. The
        // first emission of this StateFlow does the initial load that
        // `init { loadClasses() }` used to.
        viewModelScope.launch {
            sessionManager.currentUser.collect { user ->
                if (user == null) {
                    // Don't leave a stale Ready behind for the next account.
                    _uiState.value = RosterUiState.Loading
                } else {
                    loadClasses()
                }
            }
        }
    }

    fun loadClasses() {
        viewModelScope.launch {
            _uiState.value = RosterUiState.Loading
            rosterRepository.fetchClasses()
                .onSuccess { classes ->
                    when {
                        classes.isEmpty() ->
                            _uiState.value = RosterUiState.Error("No classes are assigned to your account")
                        classes.size == 1 -> selectClass(classes.first())
                        else -> _uiState.value = RosterUiState.SelectClass(classes)
                    }
                }
                .onFailure { e -> _uiState.value = RosterUiState.Error(e.message ?: "Could not load classes") }
        }
    }

    fun selectClass(teacherClass: TeacherClassDto) {
        viewModelScope.launch {
            _uiState.value = RosterUiState.Loading
            rosterRepository.syncRoster(teacherClass.sectionId, teacherClass.subjectId)
                .onSuccess {
                    sessionManager.setActiveClass(ActiveClass(teacherClass.sectionId, teacherClass.subjectId))
                    _uiState.value = RosterUiState.Ready
                }
                .onFailure { e -> _uiState.value = RosterUiState.Error(e.message ?: "Could not sync roster") }
        }
    }

    /** Skip syncing and scan against whatever's already cached locally (offline-first fallback). */
    fun continueOffline() {
        _uiState.value = RosterUiState.Ready
    }

    /**
     * Re-syncs the roster on demand (wired to the Admin Dashboard's "Sync
     * Roster" button). Without this there was no way to pull a student added
     * on the backend after the school day's roster - [loadClasses] only ever
     * runs once, at cold start (this ViewModel outlives navigating between
     * screens, so nothing re-triggers it), so a newly enrolled student
     * genuinely never appeared in the app until the process was killed and
     * restarted.
     *
     * Re-syncs the already-active class directly when one is set, rather than
     * going through [loadClasses] again - that would show the class picker
     * for a multi-class teacher even though which class is active hasn't
     * changed. Falls back to [loadClasses] only when no class is active yet
     * (e.g. the teacher chose "Continue Offline" and never synced one).
     */
    fun resyncRoster() {
        val active = sessionManager.activeClass.value
        if (active == null) {
            loadClasses()
            return
        }
        viewModelScope.launch {
            _uiState.value = RosterUiState.Loading
            rosterRepository.syncRoster(active.sectionId, active.subjectId)
                .onSuccess { _uiState.value = RosterUiState.Ready }
                .onFailure { e -> _uiState.value = RosterUiState.Error(e.message ?: "Could not sync roster") }
        }
    }
}
