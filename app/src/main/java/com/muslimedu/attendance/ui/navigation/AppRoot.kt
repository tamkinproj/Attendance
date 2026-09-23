package com.muslimedu.attendance.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.screens.admin.AdminPinScreen
import com.muslimedu.attendance.ui.screens.admin.AuditLogScreen
import com.muslimedu.attendance.ui.screens.admin.GateAdminScreen
import com.muslimedu.attendance.ui.screens.admin.SettingsScreen
import com.muslimedu.attendance.ui.screens.admin.StudentListScreen
import com.muslimedu.attendance.ui.screens.auth.LoginScreen
import com.muslimedu.attendance.ui.screens.enrollment.FaceEnrollmentScreen
import com.muslimedu.attendance.ui.screens.enrollment.PresetFaceTarget
import com.muslimedu.attendance.ui.screens.enrollment.RfidEnrollmentScreen
import com.muslimedu.attendance.ui.screens.gate.GateAttendanceScreen
import com.muslimedu.attendance.ui.screens.sync.SyncScreen
import com.muslimedu.attendance.viewmodel.AdminPinViewModel
import com.muslimedu.attendance.viewmodel.AuthState
import com.muslimedu.attendance.viewmodel.AuthViewModel
import com.muslimedu.attendance.viewmodel.PresetRfidTarget

/**
 * [requiresUnlock] screens are only shown after the device's admin PIN -
 * everything else (the gate itself, the PIN prompt, sign-in) is open.
 */
private enum class Screen(val title: String, val requiresUnlock: Boolean) {
    Gate("Gate Attendance", false),
    AdminPin("Admin", false),
    AdminHome("Admin", true),
    Students("Students", true),
    AssignCard("Assign RFID Card", true),
    EnrollFace("Enroll Face", true),
    FaceSettings("Face Verification Settings", true),
    AuditLog("Audit Log", true),
    ChangePin("Change PIN", true),
    Sync("Sync & Account", true),
    Login("Admin Sign In", false),
}

private enum class LoginPurpose { Sync, ResetPin }

/**
 * Gate-only, offline-first app: opens straight onto the gate with no login.
 * Admin screens sit behind a device PIN; signing in lives on the Sync
 * screen and is only needed to upload scans / download students.
 *
 * Classroom attendance (teacher dashboard, class scan, class roster, the
 * Leave preview, the old admin dashboard) is no longer reachable - it's done
 * on the web app now. The code is kept in the repo, just not wired in here.
 *
 * Still a state-driven switch rather than Navigation Compose: every screen
 * is at most two levels deep (gate -> admin -> tool), so "back" is a fixed
 * parent per screen ([parentOf]), not a history stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(authViewModel: AuthViewModel = hiltViewModel()) {
    val authState by authViewModel.authState.collectAsState()
    val lastLoginAt by authViewModel.lastLoginAt.collectAsState()
    val pinViewModel: AdminPinViewModel = hiltViewModel()

    var screen by remember { mutableStateOf(Screen.Gate) }
    var adminUnlocked by remember { mutableStateOf(false) }
    var loginPurpose by remember { mutableStateOf(LoginPurpose.Sync) }
    var loginOpenedAt by remember { mutableLongStateOf(0L) }
    var pinRequestId by remember { mutableLongStateOf(0L) }
    var presetFaceTarget by remember { mutableStateOf<PresetFaceTarget?>(null) }
    var presetRfidTarget by remember { mutableStateOf<PresetRfidTarget?>(null) }

    val user = (authState as? AuthState.LoggedIn)?.user

    fun navigate(to: Screen) {
        if (to == Screen.Gate) adminUnlocked = false
        if (to == Screen.AdminPin || to == Screen.ChangePin) pinRequestId = System.nanoTime()
        screen = to
    }

    fun openLogin(purpose: LoginPurpose) {
        loginPurpose = purpose
        loginOpenedAt = System.currentTimeMillis()
        navigate(Screen.Login)
    }

    fun parentOf(current: Screen): Screen = when (current) {
        Screen.Gate, Screen.AdminPin, Screen.AdminHome -> Screen.Gate
        Screen.Login -> if (loginPurpose == LoginPurpose.ResetPin) Screen.AdminPin else Screen.Sync
        else -> Screen.AdminHome
    }

    // Only a sign-in completed on the Login screen *just now* counts - never
    // a session that was already open, or the PIN would be resettable by
    // anyone holding a signed-in device.
    LaunchedEffect(lastLoginAt) {
        val at = lastLoginAt ?: return@LaunchedEffect
        if (screen != Screen.Login || at < loginOpenedAt) return@LaunchedEffect
        when (loginPurpose) {
            LoginPurpose.Sync -> navigate(Screen.Sync)
            LoginPurpose.ResetPin -> {
                pinViewModel.clearPinAfterAdminLogin()
                navigate(Screen.AdminPin)
            }
        }
    }

    LaunchedEffect(screen) {
        if (screen == Screen.Sync) authViewModel.refreshSession()
    }

    // Defensive: nothing should route here while locked, but if it does, ask for the PIN.
    val shown = if (screen.requiresUnlock && !adminUnlocked) Screen.AdminPin else screen

    BackHandler(enabled = shown != Screen.Gate) { navigate(parentOf(shown)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shown.title) },
                navigationIcon = {
                    if (shown != Screen.Gate) {
                        IconButton(onClick = { navigate(parentOf(shown)) }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    when (shown) {
                        Screen.Gate -> IconButton(onClick = {
                            navigate(if (adminUnlocked) Screen.AdminHome else Screen.AdminPin)
                        }) {
                            Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Admin")
                        }
                        Screen.AdminHome -> IconButton(onClick = { navigate(Screen.Gate) }) {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock admin")
                        }
                        else -> Unit
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (shown) {
                Screen.Gate -> GateAttendanceScreen()
                Screen.AdminPin -> AdminPinScreen(
                    onUnlocked = {
                        adminUnlocked = true
                        navigate(Screen.AdminHome)
                    },
                    onForgotPin = { openLogin(LoginPurpose.ResetPin) },
                    requestId = pinRequestId,
                    viewModel = pinViewModel,
                )
                Screen.ChangePin -> AdminPinScreen(
                    onUnlocked = { navigate(Screen.AdminHome) },
                    onForgotPin = {},
                    forceCreate = true,
                    requestId = pinRequestId,
                    viewModel = pinViewModel,
                )
                Screen.AdminHome -> GateAdminScreen(
                    onStudents = { navigate(Screen.Students) },
                    onAssignCard = {
                        presetRfidTarget = null
                        navigate(Screen.AssignCard)
                    },
                    onEnrollFace = {
                        presetFaceTarget = null
                        navigate(Screen.EnrollFace)
                    },
                    onSync = { navigate(Screen.Sync) },
                    onSettings = { navigate(Screen.FaceSettings) },
                    onAuditLog = { navigate(Screen.AuditLog) },
                    onChangePin = { navigate(Screen.ChangePin) },
                )
                Screen.Students -> StudentListScreen(
                    onRegisterFace = { student ->
                        presetFaceTarget = PresetFaceTarget(student, requestId = System.nanoTime())
                        navigate(Screen.EnrollFace)
                    },
                    onAssignCard = { student ->
                        presetRfidTarget = PresetRfidTarget(student, requestId = System.nanoTime())
                        navigate(Screen.AssignCard)
                    },
                )
                Screen.AssignCard -> RfidEnrollmentScreen(presetTarget = presetRfidTarget)
                Screen.EnrollFace -> FaceEnrollmentScreen(presetTarget = presetFaceTarget)
                Screen.FaceSettings -> SettingsScreen()
                Screen.AuditLog -> AuditLogScreen()
                Screen.Sync -> SyncScreen(
                    user = user,
                    onSignIn = { openLogin(LoginPurpose.Sync) },
                    onLogout = authViewModel::logout,
                )
                Screen.Login -> LoginScreen(
                    subtitle = if (loginPurpose == LoginPurpose.ResetPin) {
                        "Sign in with a school admin account to reset the device PIN"
                    } else {
                        "Sign in with a school admin account to upload scans and download students"
                    },
                    viewModel = authViewModel,
                )
            }
        }
    }
}
