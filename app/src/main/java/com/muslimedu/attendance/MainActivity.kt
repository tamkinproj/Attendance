package com.muslimedu.attendance

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.muslimedu.attendance.rfid.RfidManager
import com.muslimedu.attendance.ui.navigation.AppRoot
import com.muslimedu.attendance.ui.theme.MuslimEduAttendanceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Field-injected (rather than constructor-injected) because RfidManager is
    // also needed from dispatchKeyEvent, which Hilt's ComponentActivity
    // injection point supports directly; the RfidScanScreen's view model pulls
    // in the same singleton instance separately.
    @Inject
    lateinit var rfidManager: RfidManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MuslimEduAttendanceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
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
}
