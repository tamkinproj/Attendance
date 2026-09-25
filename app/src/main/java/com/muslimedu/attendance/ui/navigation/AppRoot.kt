package com.muslimedu.attendance.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.ui.components.BrandLogo
import com.muslimedu.attendance.ui.screens.SplashScreen
import com.muslimedu.attendance.ui.screens.admin.AdminPinScreen
import com.muslimedu.attendance.ui.screens.admin.AuditLogScreen
import com.muslimedu.attendance.ui.screens.admin.GateAdminScreen
import com.muslimedu.attendance.ui.screens.admin.SettingsScreen
import com.muslimedu.attendance.ui.screens.admin.StudentListScreen
import com.muslimedu.attendance.ui.screens.auth.LoginScreen
import com.muslimedu.attendance.ui.screens.enrollment.FaceEnrollmentScreen
import com.muslimedu.attendance.ui.screens.enrollment.PresetFaceTarget
import com.muslimedu.attendance.ui.screens.enrollment.RfidEnrollmentScreen
import com.muslimedu.attendance.ui.screens.gate.GateDashboardScreen
import com.muslimedu.attendance.ui.screens.gate.GateHistoryScreen
import com.muslimedu.attendance.ui.screens.gate.GateScanScreen
import com.muslimedu.attendance.ui.screens.sync.InitialSyncScreen
import com.muslimedu.attendance.ui.screens.sync.SyncScreen
import com.muslimedu.attendance.viewmodel.AdminPinViewModel
import com.muslimedu.attendance.viewmodel.AuthState
import com.muslimedu.attendance.viewmodel.AuthViewModel
import com.muslimedu.attendance.viewmodel.GateDirection
import com.muslimedu.attendance.viewmodel.PresetRfidTarget

/**
 * Admin sign-in -> sync -> gate.
 *
 * Nothing works until a school admin signs in (only `admin` accounts are
 * accepted - see AuthRepository). Each fresh sign-in is followed by the sync
 * step (upload this device's scans, download the student list). After that
 * the session is remembered, so the gate keeps working offline and across
 * restarts until an admin signs out.
 *
 * Classroom attendance (teacher dashboard, class scan, class roster, the
 * Leave preview, the old admin dashboard) is no longer reachable - it's done
 * on the web app. The code is kept in the repo, just not wired in here.
 */
@Composable
fun AppRoot(authViewModel: AuthViewModel = hiltViewModel()) {
    val authState by authViewModel.authState.collectAsState()
    val syncPending by authViewModel.postLoginSyncPending.collectAsState()

    when (val state = authState) {
        AuthState.CheckingSession -> SplashScreen()
        AuthState.LoggedOut -> LoginScreen(viewModel = authViewModel)
        is AuthState.LoggedIn ->
            if (syncPending) InitialSyncScreen(user = state.user) else GateApp(state.user, authViewModel)
    }
}

/**
 * [requiresUnlock] screens are only shown after the device's admin PIN -
 * the PIN keeps a gate attendant who isn't the admin out of card/face
 * enrollment and sync, while the gate itself stays open to them.
 */
private enum class Screen(val title: String, val requiresUnlock: Boolean) {
    Gate("Gate Attendance", false),
    GateIn("RFID Coming In", false),
    GateOut("RFID Going Out", false),
    GateHistory("RFID Scan History", false),
    AdminPin("Admin", false),
    AdminHome("Admin", true),
    Students("Students", true),
    AssignCard("Assign RFID Card", true),
    EnrollFace("Enroll Face", true),
    FaceSettings("Face Verification Settings", true),
    AuditLog("Audit Log", true),
    ChangePin("Change PIN", true),
    Sync("Sync & Account", true),
    ResetPinLogin("Reset PIN", false),
}

/**
 * The signed-in app. A state-driven switch rather than Navigation Compose:
 * every screen is at most two levels deep (gate -> admin -> tool), so "back"
 * is a fixed parent per screen ([parentOf]), not a history stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GateApp(user: UserDto, authViewModel: AuthViewModel) {
    val lastLoginAt by authViewModel.lastLoginAt.collectAsState()
    val pinViewModel: AdminPinViewModel = hiltViewModel()

    var screen by remember { mutableStateOf(Screen.Gate) }
    var adminUnlocked by remember { mutableStateOf(false) }
    var resetLoginOpenedAt by remember { mutableLongStateOf(0L) }
    var pinRequestId by remember { mutableLongStateOf(0L) }
    var presetFaceTarget by remember { mutableStateOf<PresetFaceTarget?>(null) }
    var presetRfidTarget by remember { mutableStateOf<PresetRfidTarget?>(null) }

    fun navigate(to: Screen) {
        if (to == Screen.Gate) adminUnlocked = false
        if (to == Screen.AdminPin || to == Screen.ChangePin) pinRequestId = System.nanoTime()
        if (to == Screen.ResetPinLogin) resetLoginOpenedAt = System.currentTimeMillis()
        screen = to
    }

    fun parentOf(current: Screen): Screen = when (current) {
        Screen.Gate, Screen.GateIn, Screen.GateOut, Screen.GateHistory, Screen.AdminPin, Screen.AdminHome -> Screen.Gate
        Screen.ResetPinLogin -> Screen.AdminPin
        else -> Screen.AdminHome
    }

    // Only a sign-in completed on the reset screen *just now* resets the PIN -
    // never the session that's already open, or anyone holding a signed-in
    // device could reset it.
    LaunchedEffect(lastLoginAt) {
        val at = lastLoginAt ?: return@LaunchedEffect
        if (screen != Screen.ResetPinLogin || at < resetLoginOpenedAt) return@LaunchedEffect
        pinViewModel.clearPinAfterAdminLogin()
        navigate(Screen.AdminPin)
    }

    // Defensive: nothing should route here while locked, but if it does, ask for the PIN.
    val shown = if (screen.requiresUnlock && !adminUnlocked) Screen.AdminPin else screen

    // The RFID scan screens draw their own header and handle back themselves
    // (they ask before leaving with unsynced attendance).
    val ownsChrome = shown == Screen.GateIn || shown == Screen.GateOut

    BackHandler(enabled = shown != Screen.Gate && !ownsChrome) { navigate(parentOf(shown)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!ownsChrome) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    if (shown == Screen.Gate) {
                        Column {
                            Text(shown.title, fontWeight = FontWeight.Bold)
                            Text(
                                user.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(shown.title, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    if (shown == Screen.Gate) {
                        BrandLogo(size = 32.dp, modifier = Modifier.padding(start = 12.dp, end = 4.dp))
                    } else {
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
                            Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Admin", tint = MaterialTheme.colorScheme.primary)
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
                Screen.Gate -> GateDashboardScreen(
                    onOpen = { direction -> navigate(if (direction == GateDirection.IN) Screen.GateIn else Screen.GateOut) },
                    onHistory = { navigate(Screen.GateHistory) },
                )
                Screen.GateIn -> GateScanScreen(direction = GateDirection.IN, onClose = { navigate(Screen.Gate) })
                Screen.GateOut -> GateScanScreen(direction = GateDirection.OUT, onClose = { navigate(Screen.Gate) })
                Screen.GateHistory -> GateHistoryScreen()
                Screen.AdminPin -> AdminPinScreen(
                    onUnlocked = {
                        adminUnlocked = true
                        navigate(Screen.AdminHome)
                    },
                    onForgotPin = { navigate(Screen.ResetPinLogin) },
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
                    user = user,
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
                Screen.Sync -> SyncScreen(user = user, onLogout = authViewModel::logout)
                Screen.ResetPinLogin -> LoginScreen(
                    subtitle = "Sign in again with a school admin account to reset this device's PIN",
                    syncAfterLogin = false,
                    viewModel = authViewModel,
                )
            }
        }
    }
}
