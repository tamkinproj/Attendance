package com.muslimedu.attendance.rfid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

class UsbDeviceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.d(TAG, "USB device attached")
        } else if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
            Log.d(TAG, "USB device detached")
        }
    }

    companion object {
        private const val TAG = "UsbDeviceReceiver"
    }
}
