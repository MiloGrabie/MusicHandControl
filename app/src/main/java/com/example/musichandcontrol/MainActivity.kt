package com.example.musichandcontrol

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1
        private const val ACCESSIBILITY_SERVICE_REQUEST = 2
        private const val NOTIFICATION_LISTENER_REQUEST = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Camera Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        } else {
            checkAccessibilityServiceEnabled()
        }
    }

    private fun checkAccessibilityServiceEnabled() {
        if (!isAccessibilityServiceEnabled(this, HandGestureAccessibilityService::class.java)) {
            // Prompt the user to enable the accessibility service
            Toast.makeText(
                this,
                "Please enable the Hand Gesture Accessibility Service",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, ACCESSIBILITY_SERVICE_REQUEST)
        } else {
            checkNotificationAccess()
        }
    }


    private fun checkNotificationAccess() {
        if (!isNotificationServiceEnabled(this)) {
            // Prompt the user to enable the notification listener service
            Toast.makeText(
                this,
                "Please enable Notification Access for this app",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivityForResult(intent, NOTIFICATION_LISTENER_REQUEST)
        } else {
            // All permissions are granted, start the services if needed
            startServices()
            finish() // Close the activity if you don't need to show any UI
        }
    }

    private fun startServices() {
        // Start the accessibility service if needed
        val intent = Intent(this, HandGestureAccessibilityService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // The notification listener service is started by the system when enabled
    }

    private fun isNotificationServiceEnabled(context: Context): Boolean {
        val cn = ComponentName(context, MediaControlNotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun startAccessibilityService() {
        val intent = Intent(this, HandGestureAccessibilityService::class.java)
        // The accessibility service starts itself when enabled; you don't need to start it manually
        // However, starting the service explicitly can help in some cases
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(
        context: Context,
        accessibilityServiceClass: Class<out AccessibilityService>
    ): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            val enabledServiceInfo = service.resolveInfo.serviceInfo
            if (enabledServiceInfo.packageName == context.packageName &&
                enabledServiceInfo.name == accessibilityServiceClass.name
            ) return true
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                checkAccessibilityServiceEnabled()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required for this app to function.",
                    Toast.LENGTH_LONG
                ).show()
                // Optionally, you can close the app or keep prompting
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ACCESSIBILITY_SERVICE_REQUEST -> {
                if (isAccessibilityServiceEnabled(this, HandGestureAccessibilityService::class.java)) {
                    checkNotificationAccess()
                } else {
                    Toast.makeText(
                        this,
                        "Accessibility service not enabled. Please enable it for the app to function.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            NOTIFICATION_LISTENER_REQUEST -> {
                if (isNotificationServiceEnabled(this)) {
                    // All permissions are granted, start the services if needed
                    startServices()
                    finish() // Close the activity if you don't need to show any UI
                } else {
                    Toast.makeText(
                        this,
                        "Notification access not granted. Please enable it for the app to function.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
