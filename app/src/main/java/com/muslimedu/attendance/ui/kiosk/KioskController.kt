package com.muslimedu.attendance.ui.kiosk

/**
 * The Activity-level actions kiosk mode needs
 * ([android.app.Activity.startLockTask]/`stopLockTask`,
 * `requestedOrientation`) - none of these exist on a Composable, only on
 * the hosting Activity, so [com.muslimedu.attendance.MainActivity]
 * implements this and hands an instance down to
 * [com.muslimedu.attendance.ui.navigation.AppRoot] the same way it already
 * hands down [com.muslimedu.attendance.rfid.RfidManager] - a real object,
 * not a callback threaded through five layers of composable parameters.
 *
 * [enter]/[exit] are idempotent and safe to call repeatedly (e.g. from a
 * `LaunchedEffect` keyed on "should kiosk be active right now") - calling
 * `startLockTask()` while already pinned, or `stopLockTask()` while not,
 * would otherwise crash.
 */
interface KioskController {
    /** Locks to landscape and pins the app (screen pinning / Lock Task Mode) so Home and Recents stop working. */
    fun enter()

    /** Un-pins and returns orientation to whatever the device would normally do. */
    fun exit()
}

/** For Compose previews and tests - does nothing, so a preview never accidentally locks the host device. */
object NoOpKioskController : KioskController {
    override fun enter() = Unit
    override fun exit() = Unit
}
