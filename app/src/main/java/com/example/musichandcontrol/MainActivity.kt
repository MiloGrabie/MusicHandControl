package com.example.musichandcontrol

import CameraManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.JavaCameraView
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var mOpenCvCameraView: JavaCameraView

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
        private const val ACCESSIBILITY_SETTINGS_REQUEST = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "Unable to load OpenCV!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Check and request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        } else {
            initializeApp()
        }
    }

    private fun initializeApp() {
        // Check if accessibility service is enabled
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable the accessibility service for this app", Toast.LENGTH_LONG).show()
            accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            initializeCameraManager()
        }
    }

    private val accessibilitySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (isAccessibilityServiceEnabled()) {
            initializeCameraManager()
        } else {
            Toast.makeText(this, "Accessibility service is required. Please try again.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (accessibilityEnabled == 1) {
            val services = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (services != null) {
                return services.contains("${packageName}/${HandGestureAccessibilityService::class.java.name}")
            }
        }
        return false
    }

    private fun initializeCameraManager() {
        cameraManager = CameraManager(this)
//        cameraManager.initialize(this)
//
//        // Add the camera view to the layout
//        val cameraView = cameraManager.getCameraView()
//        setContentView(cameraView)
//        cameraManager.enableCameraView()


        // Create camera view programmatically
        mOpenCvCameraView = JavaCameraView(this, CameraBridgeViewBase.CAMERA_ID_FRONT)
        mOpenCvCameraView.visibility = SurfaceView.VISIBLE
//        mOpenCvCameraView.setCvCameraViewListener(this)

        // Set the camera view as the content view
        setContentView(mOpenCvCameraView)
    }

    override fun onResume() {
        super.onResume()
        if (!OpenCVLoader.initDebug()) {
            if (OpenCVLoader.initLocal()) {
                mOpenCvCameraView.enableView()
            } else {
            }
        } else {
            mOpenCvCameraView.enableView()
        }
//        cameraManager.enableCameraView()
    }

    override fun onPause() {
        super.onPause()
        cameraManager.disableCameraView()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeApp()
            } else {
                Toast.makeText(this, "Camera permission is required for this app", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ACCESSIBILITY_SETTINGS_REQUEST) {
            if (isAccessibilityServiceEnabled()) {
                initializeCameraManager()
            } else {
                Toast.makeText(this, "Accessibility service is required. Please try again.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}