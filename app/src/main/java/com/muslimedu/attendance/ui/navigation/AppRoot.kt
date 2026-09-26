package com.muslimedu.attendance.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.ui.screens.SplashScreen
import com.muslimedu.attendance.ui.screens.admin.AdminPinScreen
import com.muslimedu.attendance.ui.screens.admin.AuditLogScreen
import com.muslimedu.attendance.ui.screens.admin.GateAdminScreen
import com.muslimedu.attendance.ui.screens.admin.GateScheduleScreen
import com.muslimedu.attendance.ui.screens.admin.ParentSmsScreen
import com.muslimedu.attendance.ui.screens.admin.SettingsScreen
import com.muslimedu.attendance.ui.screens.admin.StudentListScreen
import com.muslimedu.attendance.ui.screens.auth.LoginScreen
import com.muslimedu.attendance.ui.screens.enrollment.StudentRegistrationScreen
import com.muslimedu.attendance.ui.screens.gate.GateDashboardScreen
import com.muslimedu.attendance.ui.screens.gate.GateHistoryScreen
import com.muslimedu.attendance.ui.screens.gate.GateScanScreen
import com.muslimedu.attendance.ui.screens.sync.InitialSyncScreen
import com.muslimedu.attendance.ui.screens.sync.SyncScreen
import com.muslimedu.attendance.viewmodel.AdminPinViewModel
import com.muslimedu.attendance.viewmodel.AuthState
import com.muslimedu.attendance.viewmodel.AuthViewModel
import com.muslimedu.attendance.viewmodel.GateDirection
import com.muslimedu.attendance.viewmodel.RegistrationStart
import com.muslimedu.attendance.viewmodel.RegistrationTarget

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
    Register("Register Student", true),
    ParentSms("Parent SMS", true),
    GateSchedule("Gate Schedule", true),
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

    // Saveable: if the activity is ever recreated (rotation, theme change,
    // the system restoring the app), the admin stays on the screen they
    // were on instead of being dropped back on the dashboard.
    var screen by rememberSaveable { mutableStateOf(Screen.Gate) }
    var adminUnlocked by remember { mutableStateOf(false) }
    var resetLoginOpenedAt by remember { mutableLongStateOf(0L) }
    var pinRequestId by remember { mutableLongStateOf(0L) }
    // The wizard's student (null = its picker) and where back returns to.
    // The request id is saveable so a recreated activity carries on mid-wizard.
    var registerTarget by remember { mutableStateOf<RegistrationTarget?>(null) }
    var registerRequestId by rememberSaveable { mutableLongStateOf(0L) }
    var registerFromStudents by rememberSaveable { mutableStateOf(false) }
    // Where the PIN screen goes once unlocked (the dashboard's "set up the gate" card), and
    // whether Gate Schedule was opened from the gate rather than Admin.
    var afterUnlock by rememberSaveable { mutableStateOf<Screen?>(null) }
    var scheduleFromGate by rememberSaveable { mutableStateOf(false) }

    fun navigate(to: Screen) {
        if (to == Screen.Gate) {
            adminUnlocked = false
            afterUnlock = null
        }
        if (to == Screen.AdminPin || to == Screen.ChangePin) pinRequestId = System.nanoTime()
        if (to == Screen.ResetPinLogin) resetLoginOpenedAt = System.currentTimeMillis()
        screen = to
    }

    fun openRegistration(target: RegistrationTarget?) {
        registerTarget = target
        registerFromStudents = target != null
        registerRequestId = System.nanoTime()
        navigate(Screen.Register)
    }

    fun parentOf(current: Screen): Screen = when (current) {
        Screen.Gate, Screen.GateIn, Screen.GateOut, Screen.GateHistory, Screen.AdminPin, Screen.AdminHome -> Screen.Gate
        Screen.ResetPinLogin -> Screen.AdminPin
        Screen.Register -> if (registerFromStudents) Screen.Students else Screen.AdminHome
        Screen.GateSchedule -> if (scheduleFromGate) Screen.Gate else Screen.AdminHome
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

    // These draw their own header: the dashboard has its large title and
    // admin button, and the RFID scan screens handle back themselves (they
    // ask before leaving with unsynced attendance).
    val ownsChrome = shown == Screen.Gate || shown == Screen.GateIn || shown == Screen.GateOut

    BackHandler(enabled = !ownsChrome) { navigate(parentOf(shown)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!ownsChrome) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = { Text(shown.title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navigate(parentOf(shown)) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (shown == Screen.AdminHome) {
                        IconButton(onClick = { navigate(Screen.Gate) }) {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock admin")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (shown) {
                Screen.Gate -> GateDashboardScreen(
                    adminName = user.name,
                    onAdmin = { navigate(if (adminUnlocked) Screen.AdminHome else Screen.AdminPin) },
                    onOpen = { direction -> navigate(if (direction == GateDirection.IN) Screen.GateIn else Screen.GateOut) },
                    onHistory = { navigate(Screen.GateHistory) },
                    onSetUpSchedule = {
                        scheduleFromGate = true
                        if (adminUnlocked) {
                            navigate(Screen.GateSchedule)
                        } else {
                            navigate(Screen.AdminPin)
                            afterUnlock = Screen.GateSchedule
                        }
                    },
                )
                Screen.GateIn -> GateScanScreen(direction = GateDirection.IN, onClose = { navigate(Screen.Gate) })
                Screen.GateOut -> GateScanScreen(direction = GateDirection.OUT, onClose = { navigate(Screen.Gate) })
                Screen.GateHistory -> GateHistoryScreen()
                Screen.AdminPin -> AdminPinScreen(
                    onUnlocked = {
                        adminUnlocked = true
                        val next = afterUnlock ?: Screen.AdminHome
                        afterUnlock = null
                        navigate(next)
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
                    onRegister = { openRegistration(null) },
                    onParentSms = { navigate(Screen.ParentSms) },
                    onGateSchedule = {
                        scheduleFromGate = false
                        navigate(Screen.GateSchedule)
                    },
                    onSync = { navigate(Screen.Sync) },
                    onSettings = { navigate(Screen.FaceSettings) },
                    onAuditLog = { navigate(Screen.AuditLog) },
                    onChangePin = { navigate(Screen.ChangePin) },
                )
                Screen.Students -> StudentListScreen(
                    onRegisterFace = { student -> openRegistration(RegistrationTarget(student, RegistrationStart.FACE)) },
                    onAssignCard = { student -> openRegistration(RegistrationTarget(student, RegistrationStart.CARD)) },
                    onParentPhone = { student -> openRegistration(RegistrationTarget(student, RegistrationStart.PHONE)) },
                )
                Screen.Register -> StudentRegistrationScreen(
                    target = registerTarget,
                    requestId = registerRequestId,
                    onFinish = { navigate(parentOf(Screen.Register)) },
                )
                Screen.GateSchedule -> GateScheduleScreen(onSaved = { navigate(parentOf(Screen.GateSchedule)) })
                Screen.FaceSettings -> SettingsScreen()
                Screen.AuditLog -> AuditLogScreen()
                Screen.ParentSms -> ParentSmsScreen()
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
