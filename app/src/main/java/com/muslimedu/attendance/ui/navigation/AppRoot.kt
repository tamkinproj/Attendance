package com.muslimedu.attendance.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.ui.screens.SplashScreen
import com.muslimedu.attendance.ui.screens.admin.AdminDashboardScreen
import com.muslimedu.attendance.ui.screens.admin.AdminDirectoryScreen
import com.muslimedu.attendance.ui.screens.admin.AuditLogScreen
import com.muslimedu.attendance.ui.screens.admin.ExportScreen
import com.muslimedu.attendance.ui.screens.admin.GateAttendanceScreen
import com.muslimedu.attendance.ui.screens.admin.SettingsScreen
import com.muslimedu.attendance.ui.screens.admin.StudentListScreen
import com.muslimedu.attendance.ui.screens.admin.SyncStatusScreen
import com.muslimedu.attendance.ui.screens.auth.LoginScreen
import com.muslimedu.attendance.ui.screens.dashboard.ClassRosterScreen
import com.muslimedu.attendance.ui.screens.dashboard.TeacherDashboardScreen
import com.muslimedu.attendance.ui.screens.enrollment.FaceEnrollmentScreen
import com.muslimedu.attendance.ui.screens.enrollment.PresetFaceTarget
import com.muslimedu.attendance.ui.screens.enrollment.RfidEnrollmentScreen
import com.muslimedu.attendance.ui.screens.profile.ProfileScreen
import com.muslimedu.attendance.ui.screens.roster.RosterGateScreen
import com.muslimedu.attendance.ui.screens.scanner.RfidScanScreen
import com.muslimedu.attendance.viewmodel.AuthState
import com.muslimedu.attendance.viewmodel.AuthViewModel
import com.muslimedu.attendance.viewmodel.PresetRfidTarget
import com.muslimedu.attendance.viewmodel.PresetRfidUid
import com.muslimedu.attendance.viewmodel.RosterUiState
import com.muslimedu.attendance.viewmodel.RosterViewModel

private val ADMIN_ROLES = setOf("admin", "superadmin")

private enum class Destination(val title: String) {
    /** The teacher's home - TeacherDashboardScreen once a class roster is ready, or the class picker/loading/error state otherwise (see [LoggedInContent]). */
    Home("Attendance"),
    ScanAttendance("Scan Attendance"),
    ClassRoster("Class Roster"),
    Profile("Profile"),
    AdminDashboard("Admin Dashboard"),
    SyncStatus("Sync Status"),
    StudentList("Students"),
    AdminDirectory("Browse by Class"),
    GateAttendance("Gate In/Out Attendance"),
    FaceEnrollment("Enroll Face"),
    RfidEnrollment("Assign RFID Card"),
    Settings("Face Verification Settings"),
    Export("Export Attendance"),
    AuditLog("Audit Log"),
}

/** One tab in the bottom nav strip - a small fast-access layer, not a real back stack (see [AppRoot]'s own doc comment). */
private data class BottomNavItem(val destination: Destination, val label: String, val icon: ImageVector)

private val TEACHER_BOTTOM_NAV = listOf(
    BottomNavItem(Destination.Home, "Home", Icons.Filled.Dashboard),
    BottomNavItem(Destination.ScanAttendance, "Scan", Icons.Filled.CreditCard),
    BottomNavItem(Destination.ClassRoster, "Roster", Icons.Filled.Groups),
    BottomNavItem(Destination.Profile, "Profile", Icons.Filled.Person),
)

private val ADMIN_BOTTOM_NAV = listOf(
    BottomNavItem(Destination.AdminDashboard, "Dashboard", Icons.Filled.Dashboard),
    BottomNavItem(Destination.ScanAttendance, "Scan", Icons.Filled.CreditCard),
    BottomNavItem(Destination.StudentList, "Students", Icons.Filled.People),
    BottomNavItem(Destination.Settings, "Settings", Icons.Filled.Settings),
)

/**
 * No dedicated navigation graph yet - a state-driven switch is simpler than
 * pulling in Navigation Compose for this many destinations. Revisit once
 * there's a real need for deep linking or back-stack history beyond "one
 * level below home".
 *
 * The bottom nav strip added here is deliberately shallow: it only shows at
 * a small set of top-level destinations per role (see [TEACHER_BOTTOM_NAV]/
 * [ADMIN_BOTTOM_NAV]) and just sets [destination] directly, same as every
 * other navigation call in this file - it doesn't change the "back always
 * returns to Home" model below, it's a faster way to reach four destinations
 * without needing an in-between screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(authViewModel: AuthViewModel = hiltViewModel()) {
    val authState by authViewModel.authState.collectAsState()
    var destination by remember { mutableStateOf(Destination.Home) }
    var presetFaceTarget by remember { mutableStateOf<PresetFaceTarget?>(null) }
    var presetRfidTarget by remember { mutableStateOf<PresetRfidTarget?>(null) }
    var presetRfidUid by remember { mutableStateOf<PresetRfidUid?>(null) }

    when (val state = authState) {
        is AuthState.CheckingSession -> SplashScreen()
        is AuthState.LoggedOut -> LoginScreen()
        is AuthState.LoggedIn -> {
            val isAdmin = state.user.role in ADMIN_ROLES
            // Hoisted here (rather than let LoggedInContent default to its own
            // hiltViewModel() call) so the Admin Dashboard's "Sync Roster"
            // button and TeacherDashboardScreen's "My Classes" quick action
            // can drive the exact same instance - there's no
            // Navigation-Compose back stack in this app, so every
            // hiltViewModel<RosterViewModel>() call in this Activity would
            // already resolve to the same instance regardless, but passing it
            // explicitly makes that sharing visible instead of incidental.
            val rosterViewModel: RosterViewModel = hiltViewModel()
            val rosterState by rosterViewModel.uiState.collectAsState()

            // `destination` is remembered outside this branch, so it survives
            // a logout: without this, logging out from an admin screen and
            // back in as a teacher dropped that teacher straight onto the
            // Admin Dashboard. Keyed on the account, so it only resets when
            // who's logged in actually changes - not on every recomposition.
            // The preset targets carry a StudentEntity (or a scanned UID)
            // from the previous account, so they go too.
            LaunchedEffect(state.user.id) {
                destination = Destination.Home
                presetFaceTarget = null
                presetRfidTarget = null
                presetRfidUid = null
            }

            val bottomNavItems = if (isAdmin) ADMIN_BOTTOM_NAV else TEACHER_BOTTOM_NAV
            val showBottomNav = bottomNavItems.any { it.destination == destination }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (destination == Destination.Home) state.user.name else destination.title) },
                        navigationIcon = {
                            if (destination != Destination.Home) {
                                IconButton(onClick = { destination = Destination.Home }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        actions = {
                            if (destination == Destination.Home && isAdmin) {
                                IconButton(onClick = { destination = Destination.AdminDashboard }) {
                                    Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Admin Dashboard")
                                }
                            }
                            IconButton(onClick = authViewModel::logout) {
                                Icon(Icons.Filled.Logout, contentDescription = "Logout")
                            }
                        },
                    )
                },
                bottomBar = {
                    if (showBottomNav) {
                        NavigationBar {
                            bottomNavItems.forEach { item ->
                                NavigationBarItem(
                                    selected = destination == item.destination,
                                    onClick = { destination = item.destination },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    when (destination) {
                        Destination.Home -> LoggedInContent(
                            rosterViewModel = rosterViewModel,
                            rosterState = rosterState,
                            onScanAttendance = { destination = Destination.ScanAttendance },
                            onMyClasses = rosterViewModel::loadClasses,
                            onClassRoster = { destination = Destination.ClassRoster },
                        )
                        Destination.ScanAttendance -> RfidScanScreen(
                            isAdmin = isAdmin,
                            onAssignToStudent = { uid ->
                                presetRfidTarget = null
                                presetRfidUid = PresetRfidUid(uid, requestId = System.nanoTime())
                                destination = Destination.RfidEnrollment
                            },
                        )
                        Destination.ClassRoster -> ClassRosterScreen(isAdmin = isAdmin)
                        Destination.Profile -> ProfileScreen(user = state.user, onLogout = authViewModel::logout)
                        Destination.AdminDashboard -> AdminDashboardScreen(
                            onManageStudents = { destination = Destination.StudentList },
                            onEnrollFace = {
                                presetFaceTarget = null
                                destination = Destination.FaceEnrollment
                            },
                            onEnrollRfid = {
                                presetRfidTarget = null
                                presetRfidUid = null
                                destination = Destination.RfidEnrollment
                            },
                            onTakeAttendance = { destination = Destination.ScanAttendance },
                            onSettings = { destination = Destination.Settings },
                            onExportAttendance = { destination = Destination.Export },
                            onSyncRoster = rosterViewModel::resyncRoster,
                            isSyncingRoster = rosterState is RosterUiState.Loading,
                            onBrowseByClass = { destination = Destination.AdminDirectory },
                            onGateAttendance = { destination = Destination.GateAttendance },
                            onSyncStatus = { destination = Destination.SyncStatus },
                            onAuditLog = { destination = Destination.AuditLog },
                        )
                        Destination.SyncStatus -> SyncStatusScreen()
                        Destination.StudentList -> StudentListScreen(
                            onRegisterFace = { student ->
                                presetFaceTarget = PresetFaceTarget(student, requestId = System.nanoTime())
                                destination = Destination.FaceEnrollment
                            },
                            onAssignCard = { student ->
                                presetRfidUid = null
                                presetRfidTarget = PresetRfidTarget(student, requestId = System.nanoTime())
                                destination = Destination.RfidEnrollment
                            },
                        )
                        Destination.AdminDirectory -> AdminDirectoryScreen(
                            onRegisterFace = { student ->
                                presetFaceTarget = PresetFaceTarget(student, requestId = System.nanoTime())
                                destination = Destination.FaceEnrollment
                            },
                            onAssignCard = { student ->
                                presetRfidUid = null
                                presetRfidTarget = PresetRfidTarget(student, requestId = System.nanoTime())
                                destination = Destination.RfidEnrollment
                            },
                        )
                        Destination.GateAttendance -> GateAttendanceScreen()
                        Destination.FaceEnrollment -> FaceEnrollmentScreen(presetTarget = presetFaceTarget)
                        Destination.RfidEnrollment -> RfidEnrollmentScreen(presetTarget = presetRfidTarget, presetUid = presetRfidUid)
                        Destination.Settings -> SettingsScreen()
                        Destination.Export -> ExportScreen()
                        Destination.AuditLog -> AuditLogScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun LoggedInContent(
    rosterViewModel: RosterViewModel,
    rosterState: RosterUiState,
    onScanAttendance: () -> Unit,
    onMyClasses: () -> Unit,
    onClassRoster: () -> Unit,
) {
    when (rosterState) {
        is RosterUiState.Ready -> TeacherDashboardScreen(
            onScanAttendance = onScanAttendance,
            onMyClasses = onMyClasses,
            onClassRoster = onClassRoster,
        )
        else -> RosterGateScreen(
            state = rosterState,
            onSelectClass = rosterViewModel::selectClass,
            onRetry = rosterViewModel::loadClasses,
            onContinueOffline = rosterViewModel::continueOffline,
        )
    }
}
