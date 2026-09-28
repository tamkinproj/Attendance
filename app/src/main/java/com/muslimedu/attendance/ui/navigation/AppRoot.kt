package com.muslimedu.attendance.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.data.remote.dto.UserDto
import com.muslimedu.attendance.ui.kiosk.KioskController
import com.muslimedu.attendance.ui.kiosk.NoOpKioskController
import com.muslimedu.attendance.ui.screens.SplashScreen
import com.muslimedu.attendance.ui.screens.admin.AdminPinScreen
import com.muslimedu.attendance.ui.screens.admin.AuditLogScreen
import com.muslimedu.attendance.ui.screens.admin.GateAdminScreen
import com.muslimedu.attendance.ui.screens.admin.GateScheduleScreen
import com.muslimedu.attendance.ui.screens.admin.KioskModeScreen
import com.muslimedu.attendance.ui.screens.admin.ParentSmsScreen
import com.muslimedu.attendance.ui.screens.admin.SettingsScreen
import com.muslimedu.attendance.ui.screens.admin.StudentListScreen
import com.muslimedu.attendance.ui.screens.auth.LoginScreen
import com.muslimedu.attendance.ui.screens.enrollment.StudentRegistrationScreen
import com.muslimedu.attendance.ui.screens.gate.GateDashboardScreen
import com.muslimedu.attendance.ui.screens.gate.GateHistoryScreen
import com.muslimedu.attendance.ui.screens.gate.GateScanScreen
import com.muslimedu.attendance.ui.screens.gate.GateSummaryScreen
import com.muslimedu.attendance.ui.screens.sync.InitialSyncScreen
import com.muslimedu.attendance.ui.screens.sync.SyncScreen
import com.muslimedu.attendance.viewmodel.AdminPinViewModel
import com.muslimedu.attendance.viewmodel.AuthState
import com.muslimedu.attendance.viewmodel.AuthViewModel
import com.muslimedu.attendance.viewmodel.GateDirection
import com.muslimedu.attendance.viewmodel.KioskModeViewModel
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
fun AppRoot(
    authViewModel: AuthViewModel = hiltViewModel(),
    /**
     * True only on a tablet ([com.muslimedu.attendance.util.isTabletFormFactor]).
     * Only gates the Admin nav rail below - kiosk mode itself now runs on
     * either form factor, driven purely by DeviceSettings.kioskModeEnabled.
     */
    isTabletDevice: Boolean = false,
    kioskController: KioskController = NoOpKioskController,
) {
    val authState by authViewModel.authState.collectAsState()
    val syncPending by authViewModel.postLoginSyncPending.collectAsState()

    when (val state = authState) {
        AuthState.CheckingSession -> SplashScreen()
        AuthState.LoggedOut -> LoginScreen(viewModel = authViewModel)
        is AuthState.LoggedIn ->
            if (syncPending) InitialSyncScreen(user = state.user) else GateApp(state.user, authViewModel, isTabletDevice, kioskController)
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
    AttendanceSummary("Attendance Summary", true),
    Register("Register Student", true),
    ParentSms("Parent SMS", true),
    GateSchedule("Gate Schedule", true),
    FaceSettings("Face Verification Settings", true),
    AuditLog("Audit Log", true),
    ChangePin("Change PIN", true),
    Sync("Sync & Account", true),
    KioskMode("Kiosk Mode", true),
    ResetPinLogin("Reset PIN", false),
}

/**
 * The Admin tools a tablet's [NavigationRail] switches between (see
 * [AdminNavigationRail]) - the same set [GateAdminScreen]'s tile grid
 * already links to, just reachable without a trip back through
 * [Screen.AdminHome] each time. [Register] is included for parity with the
 * grid even though the wizard screen itself never shows the rail (it owns
 * its own full-screen chrome, like [Screen.Register] does everywhere else).
 */
private enum class AdminSection(val icon: ImageVector, val label: String, val screen: Screen) {
    Dashboard(Icons.Filled.AdminPanelSettings, "Dashboard", Screen.AdminHome),
    Register(Icons.Filled.HowToReg, "Register", Screen.Register),
    Students(Icons.Filled.People, "Students", Screen.Students),
    Summary(Icons.Filled.CalendarToday, "Summary", Screen.AttendanceSummary),
    ParentSms(Icons.Filled.Sms, "Parent SMS", Screen.ParentSms),
    Schedule(Icons.Filled.Schedule, "Schedule", Screen.GateSchedule),
    Sync(Icons.Filled.CloudSync, "Sync", Screen.Sync),
    FaceSettings(Icons.Filled.Tune, "Face", Screen.FaceSettings),
    AuditLog(Icons.Filled.History, "Audit Log", Screen.AuditLog),
    ChangePin(Icons.Filled.Lock, "Change PIN", Screen.ChangePin),
    Kiosk(Icons.Filled.Tablet, "Kiosk", Screen.KioskMode),
}

/**
 * A persistent side rail for switching between Admin tools on a tablet -
 * the "nav rail ... for Admin screens on a tablet that isn't in kiosk mode"
 * follow-up to Kiosk Mode (which only handles the locked-down stand
 * scenario). Purely a faster way to reach the same destinations
 * [GateAdminScreen]'s tile grid already does - it doesn't replace that grid
 * (still shown as the content pane at [Screen.AdminHome]) or change what
 * "back" does from any tool ([parentOf] is untouched).
 */
@Composable
private fun AdminNavigationRail(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationRail {
        AdminSection.entries.forEach { section ->
            NavigationRailItem(
                selected = current == section.screen,
                onClick = { onSelect(section.screen) },
                icon = { Icon(section.icon, contentDescription = section.label) },
                label = { Text(section.label) },
            )
        }
    }
}

/**
 * The signed-in app. A state-driven switch rather than Navigation Compose:
 * every screen is at most two levels deep (gate -> admin -> tool), so "back"
 * is a fixed parent per screen ([parentOf]), not a history stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GateApp(user: UserDto, authViewModel: AuthViewModel, isTabletDevice: Boolean, kioskController: KioskController) {
    val lastLoginAt by authViewModel.lastLoginAt.collectAsState()
    val pinViewModel: AdminPinViewModel = hiltViewModel()
    val kioskViewModel: KioskModeViewModel = hiltViewModel()
    val kioskModeEnabled by kioskViewModel.kioskModeEnabled.collectAsState()
    // Kiosk mode is engaged (screen pinned, forced landscape + dark, no way
    // out through Back) whenever the setting is on - a phone or a tablet,
    // see DeviceSettings.kioskModeEnabled's doc comment for why this no
    // longer checks isTabletDevice. MainActivity reads the same value
    // independently to drive the actual Activity APIs
    // (startLockTask/requestedOrientation/forced dark theme); this copy is
    // only for what's rendered here (the back-button swallow below, and the
    // kiosk look passed into the gate screens).
    val kioskActive = kioskModeEnabled

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

    // The tablet nav rail's own entry point - mirrors GateAdminScreen's tile
    // onClick lambdas (Register goes through the wizard's own request-id
    // plumbing, Gate Schedule is always "from Admin" since the rail is only
    // ever shown once already inside Admin) rather than a bare `navigate()`,
    // so switching tools from the rail behaves exactly like tapping the
    // matching tile would have.
    fun navigateFromRail(target: Screen) {
        when (target) {
            Screen.Register -> openRegistration(null)
            Screen.GateSchedule -> {
                scheduleFromGate = false
                navigate(Screen.GateSchedule)
            }
            else -> navigate(target)
        }
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
    // admin button, the RFID scan screens handle back themselves (they ask
    // before leaving with unsynced attendance), and the Register wizard's
    // face step takes the whole screen.
    val ownsChrome = shown == Screen.Gate || shown == Screen.GateIn || shown == Screen.GateOut || shown == Screen.Register

    BackHandler(enabled = !ownsChrome) { navigate(parentOf(shown)) }
    // GateIn/GateOut already handle Back themselves (GateScanScreen's own
    // BackHandler, always on, asks before leaving unsynced attendance) -
    // that's untouched by kiosk mode, on purpose: it never exits the app
    // either way. The gap kiosk mode actually closes is Screen.Gate and
    // Screen.Register, which own their chrome but register no BackHandler
    // of their own - outside kiosk that's fine (there's nothing after them
    // to protect), but it means Back on the idle gate dashboard normally
    // finishes the Activity, exactly what a student touching Back at a
    // kiosk stand must not do. Swallows the event; does nothing else.
    BackHandler(enabled = kioskActive && (shown == Screen.Gate || shown == Screen.Register)) {}

    // A persistent side rail for switching between Admin tools, tablet-only
    // and never while kiosk mode is engaged (a kiosk stand has no admin in
    // front of it) - the Register wizard is excluded because it already
    // owns its own full-screen chrome (see AdminSection's own doc comment).
    val showAdminRail = isTabletDevice && !kioskActive && shown.requiresUnlock && shown != Screen.Register

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
        val content: @Composable () -> Unit = {
            when (shown) {
                Screen.Gate -> GateDashboardScreen(
                    adminName = user.name,
                    kiosk = kioskActive,
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
                Screen.GateIn -> GateScanScreen(direction = GateDirection.IN, kiosk = kioskActive, onClose = { navigate(Screen.Gate) })
                Screen.GateOut -> GateScanScreen(direction = GateDirection.OUT, kiosk = kioskActive, onClose = { navigate(Screen.Gate) })
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
                    onKioskMode = { navigate(Screen.KioskMode) },
                    onAttendanceSummary = { navigate(Screen.AttendanceSummary) },
                )
                Screen.AttendanceSummary -> GateSummaryScreen()
                Screen.Students -> StudentListScreen(
                    onRegisterFace = { student -> openRegistration(RegistrationTarget(student, RegistrationStart.FACE)) },
                    onAssignCard = { student -> openRegistration(RegistrationTarget(student, RegistrationStart.CARD)) },
                    onParentPhone = { student -> openRegistration(RegistrationTarget(student, RegistrationStart.PHONE)) },
                )
                Screen.Register -> StudentRegistrationScreen(
                    target = registerTarget,
                    requestId = registerRequestId,
                    onFinish = { navigate(parentOf(Screen.Register)) },
                    onBack = { navigate(parentOf(Screen.Register)) },
                )
                Screen.GateSchedule -> GateScheduleScreen(onSaved = { navigate(parentOf(Screen.GateSchedule)) })
                Screen.FaceSettings -> SettingsScreen()
                Screen.AuditLog -> AuditLogScreen()
                Screen.KioskMode -> KioskModeScreen(isTabletDevice = isTabletDevice, kioskController = kioskController, viewModel = kioskViewModel)
                Screen.ParentSms -> ParentSmsScreen()
                Screen.Sync -> SyncScreen(user = user, onLogout = authViewModel::logout)
                Screen.ResetPinLogin -> LoginScreen(
                    subtitle = "Sign in again with a school admin account to reset this device's PIN",
                    syncAfterLogin = false,
                    viewModel = authViewModel,
                )
            }
        }
        Box(modifier = Modifier.padding(padding)) {
            if (showAdminRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    AdminNavigationRail(current = shown, onSelect = ::navigateFromRail)
                    Box(modifier = Modifier.weight(1f)) { content() }
                }
            } else {
                content()
            }
        }
    }
}
