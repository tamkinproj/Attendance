package com.muslimedu.attendance

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.muslimedu.attendance.data.local.DeviceSettings
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.sync.DeviceHealthReporter
import com.muslimedu.attendance.ui.kiosk.KioskController
import com.muslimedu.attendance.ui.navigation.AppRoot
import com.muslimedu.attendance.ui.theme.MuslimEduAttendanceTheme
import com.muslimedu.attendance.util.isTabletFormFactor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity(), KioskController {

    // Field-injected (rather than constructor-injected) because RfidManager is
    // also needed from dispatchKeyEvent, which Hilt's ComponentActivity
    // injection point supports directly; the RfidScanScreen's view model pulls
    // in the same singleton instance separately.
    @Inject
    lateinit var rfidManager: RfidManager

    @Inject
    lateinit var deviceHealthReporter: DeviceHealthReporter

    @Inject
    lateinit var deviceSettings: DeviceSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTabletDevice = isTabletFormFactor(this)
        setContent {
            val kioskModeEnabled by deviceSettings.kioskModeEnabled.collectAsState()
            // Only a tablet with the setting on actually engages kiosk mode -
            // see DeviceSettings.kioskModeEnabled's own doc comment for why
            // the setting itself doesn't gate on the device.
            val kioskEngaged = isTabletDevice && kioskModeEnabled

            // Screen pinning + landscape are Activity APIs, applied here (not
            // from a Composable) as soon as whether kiosk should be active
            // changes - covers a cold start with the setting already on, and
            // the admin flipping it while the app is running.
            LaunchedEffect(kioskEngaged) { if (kioskEngaged) enter() else exit() }

            MuslimEduAttendanceTheme(darkTheme = kioskEngaged) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(
                        isTabletDevice = isTabletDevice,
                        kioskController = this@MainActivity,
                    )
                }
            }
        }
    }

    // The web's Gate Devices page says whether the app is actually open on the gate phone.
    override fun onStart() {
        super.onStart()
        deviceHealthReporter.appVisible = true
    }

    override fun onStop() {
        deviceHealthReporter.appVisible = false
        super.onStop()
    }

    /**
     * Cheap USB RFID readers enumerate as a HID keyboard and "type" the card
     * UID as digits + Enter; the OS delivers that as ordinary key events to
     * whichever view has focus. Intercepting here means the reader works
     * without needing a specific view/EditText to hold focus.
     *
     * Except while a text field is focused (sign-in, PIN, add student):
     * those keys are someone typing, so they go to the field. The gate and
     * card screens have no text fields, so the reader is never blocked there.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val typingInField = currentFocus?.onCheckIsTextEditor() == true
        if (!typingInField && ::rfidManager.isInitialized && rfidManager.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ── KioskController ──────────────────────────────────────────────────
    // See KioskController's own doc comment for why this lives on the
    // Activity: startLockTask()/stopLockTask()/requestedOrientation only
    // exist there, not on a Composable.

    private fun currentLockTaskState(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE
    }

    /**
     * `startLockTask()` (screen pinning) needs no device-owner/MDM
     * enrollment - any app can call it - but the *first* time, stock
     * Android shows its own "Screen pinning" explanation and asks the
     * person holding the device to confirm; after that it just pins
     * silently. It's what actually stops Home and Recents from working;
     * the only way out without the admin PIN is the OS's own unpin
     * gesture (hold Back + Recents, or swipe up and hold, depending on
     * the device's navigation style) - genuinely not something a device-
     * owner-provisioned "real" kiosk would need, but that needs a factory-
     * reset provisioning step this app can't do by itself (see
     * KioskModeScreen's own note on this). Wrapped in runCatching: a
     * device that refuses pinning (disabled by an MDM, or an OEM quirk)
     * should still get the landscape lock and back-button suppression
     * rather than crashing the app.
     */
    override fun enter() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (currentLockTaskState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { startLockTask() }
        }
    }

    override fun exit() {
        if (currentLockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { stopLockTask() }
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
