package com.muslimedu.attendance.data.session

import com.muslimedu.attendance.data.remote.dto.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveClass(val sectionId: Int, val subjectId: Int)

/**
 * Holds in-memory state parts of the app outside the Compose tree
 * (repositories, other view models) need without prop-drilling it down from
 * [com.muslimedu.attendance.ui.navigation.AppRoot]:
 * - the logged-in teacher's profile (specifically `school_id`), set by
 *   [com.muslimedu.attendance.viewmodel.AuthViewModel] on login/session
 *   validation, cleared on logout.
 * - the section/subject the teacher is currently scanning for, set by
 *   [com.muslimedu.attendance.viewmodel.RosterViewModel] once a roster sync
 *   succeeds (or the user picks "Continue Offline", in which case it's left
 *   null - see [com.muslimedu.attendance.data.repository.AttendanceRepository]
 *   for how that's handled).
 *
 * Nothing here is persisted - on a fresh process start it's null until
 * AuthViewModel's startup check populates it again.
 */
@Singleton
class SessionManager @Inject constructor() {
    private val _currentUser = MutableStateFlow<UserDto?>(null)
    val currentUser: StateFlow<UserDto?> = _currentUser.asStateFlow()

    private val _activeClass = MutableStateFlow<ActiveClass?>(null)
    val activeClass: StateFlow<ActiveClass?> = _activeClass.asStateFlow()

    fun setUser(user: UserDto?) {
        _currentUser.value = user
    }

    fun setActiveClass(activeClass: ActiveClass?) {
        _activeClass.value = activeClass
    }
}
