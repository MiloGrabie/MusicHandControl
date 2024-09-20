package com.example.musichandcontrol

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaController
import com.example.musichandcontrol.ui.theme.MusicHandControlTheme
import com.google.common.collect.ImmutableList
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import org.opencv.android.CameraActivity
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.JavaCameraView
import org.opencv.android.NativeCameraView.TAG
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import java.io.File
import java.io.FileOutputStream
import android.os.Build
import android.provider.Settings
import com.google.mediapipe.framework.AndroidPacketCreator
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsOptions
import com.google.mediapipe.solutions.hands.HandsResult
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.framework.PacketCreator
import com.google.mlkit.vision.common.InputImage
import org.opencv.android.Utils
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : CameraActivity(), CameraBridgeViewBase.CvCameraViewListener2 {
    private lateinit var mOpenCvCameraView: JavaCameraView    
    private var handLandmarks: ImmutableList<NormalizedLandmarkList> = ImmutableList.of()
    private lateinit var hands: Hands
    private var lastGestureTime: Long = 0
    private val GESTURE_COOLDOWN = 500 // ms cooldown between gestures

    override fun getCameraViewList(): List<CameraBridgeViewBase> {
        return listOf(mOpenCvCameraView)
    }
    
    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_REQUEST = 1
        private const val ACCESSIBILITY_SETTINGS_REQUEST = 2

        // Define hand connections
        private val HAND_CONNECTIONS = listOf(
            Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
            Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
            Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
            Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
            Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
            Pair(5, 9), Pair(9, 13), Pair(13, 17)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Unable to load OpenCV!")
        } else {
            Log.d("OpenCV", "OpenCV loaded Successfully!")
        }

        // Create camera view programmatically
        mOpenCvCameraView = JavaCameraView(this, CameraBridgeViewBase.CAMERA_ID_FRONT)
        mOpenCvCameraView.visibility = SurfaceView.VISIBLE
        mOpenCvCameraView.setCvCameraViewListener(this)

        // Set the camera view as the content view
        setContentView(mOpenCvCameraView)

        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        } else {
            mOpenCvCameraView.enableView()
        }

        // Check and wait for accessibility service to be enabled
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable the accessibility service for this app", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, ACCESSIBILITY_SETTINGS_REQUEST)
        } else {
            continueInitialization()
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
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ACCESSIBILITY_SETTINGS_REQUEST) {
            if (isAccessibilityServiceEnabled()) {
                continueInitialization()
            } else {
                // If the user didn't enable the service, show a message and finish the activity
                Toast.makeText(this, "Accessibility service is required. Please try again.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // Add this method to continue the initialization process
    private fun continueInitialization() {
        if (!isNotificationServiceEnabled()) {
            Toast.makeText(this, "Please enable notification access for this app", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    
        // Start the background service
//        val serviceIntent = Intent(this, BackgroundService::class.java)
//        startForegroundService(serviceIntent)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MediaControlNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mOpenCvCameraView.enableView()
            } else {
                Log.e("OpenCV", "Camera permission not granted!")
            }
        }
    }

    private fun initializeHandTracking() {
        val options = HandsOptions.builder()
            .setStaticImageMode(false)
            .setMaxNumHands(2)
            .setRunOnGpu(true)
            .build()

        hands = Hands(this, options)

        hands.setResultListener { handsResult ->
            processHandTrackingResult(handsResult)
        }

//        hands.addOnErrorListener { message, domain ->
//            Log.e("HandTracking", "Error: $message, $domain")
//        }
    }

    override fun onResume() {
        super.onResume()
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization")
            if (OpenCVLoader.initLocal()) {
                Log.i(TAG, "OpenCV loaded successfully")
                mOpenCvCameraView.enableView()
                initializeHandTracking()
            } else {
                Log.e(TAG, "OpenCV initialization failed")
            }
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!")
            mOpenCvCameraView.enableView()
            initializeHandTracking()
        }
    }

    override fun onPause() {
        super.onPause()
        mOpenCvCameraView.disableView()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mOpenCvCameraView.disableView()

    }

    override fun onCameraViewStarted(width: Int, height: Int) {}

    override fun onCameraViewStopped() {}

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        val frame = inputFrame.rgba()

        // Convert Mat to Bitmap
        val bitmap = Bitmap.createBitmap(frame.cols(), frame.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(frame, bitmap)

        // Get current timestamp in microseconds
        val timestamp = System.nanoTime() / 1000

        // Process the image with MediaPipe Hands
        hands.send(bitmap, timestamp)

        // Draw rectangles for detected hands
        drawHandLandmarks(frame)
    
        return frame
    }

    private fun processHandTrackingResult(result: HandsResult) {
        handLandmarks = result.multiHandLandmarks()
        var localMediaController = MediaControlNotificationListener.mediaController

        if (handLandmarks.isNotEmpty()) {
            val currentTime = System.currentTimeMillis()
            val landmarks = handLandmarks[0]

            when {
                detectPinchAndMoveGesture(landmarks) -> {
                    val direction = getPinchMoveDirection()
                    if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                        if (direction == PinchMoveDirection.LEFT) {
                            Log.d(TAG, "Gesture detected: Pinch and Move Left")
                            localMediaController?.transportControls?.skipToPrevious()
                            lastGestureTime = currentTime
                        } else if (direction == PinchMoveDirection.RIGHT) {
                            Log.d(TAG, "Gesture detected: Pinch and Move Right")
                            localMediaController?.transportControls?.skipToNext()
                            lastGestureTime = currentTime
                        }
                    }
                }
                detectClosedHand(landmarks) -> {
                    if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                        Log.d(TAG, "Gesture detected: Closed Hand")
                        localMediaController?.transportControls?.pause()
                        lastGestureTime = currentTime
                    }
                }
                detectOpenHand(landmarks) -> {
                    if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                        Log.d(TAG, "Gesture detected: Open Hand")
                        localMediaController?.transportControls?.play()
                        lastGestureTime = currentTime
                    }
                }
            }
        }
    }

    private var previousPinchX: Float? = null
    private var previousTime: Long = 0
    private val PINCH_MOVE_THRESHOLD = 0.005f // Adjust this threshold based on testing
    private var pinchMoveDirection: PinchMoveDirection? = null

    private enum class PinchMoveDirection { LEFT, RIGHT }

    private fun detectPinchAndMoveGesture(landmarks: NormalizedLandmarkList): Boolean {
        val thumb = landmarks.landmarkList[4]
        val index = landmarks.landmarkList[8]

        // Check if thumb and index are close (pinched)
        val isPinched = distance(thumb, index) < 0.05f // Adjust this threshold as needed

        if (!isPinched) {
            previousPinchX = null
            return false
        }

        val currentPinchX = (thumb.x + index.x) / 2 // Average X position of thumb and index
        val currentTime = System.currentTimeMillis()

        var isPinchMove = false

        if (previousPinchX != null && currentTime - previousTime < 500) { // 500ms window
            val deltaX = currentPinchX - previousPinchX!!

            // Check if the pinch has moved enough
            if (abs(deltaX) > PINCH_MOVE_THRESHOLD) {
                isPinchMove = true
                pinchMoveDirection = if (deltaX < 0) PinchMoveDirection.RIGHT else PinchMoveDirection.LEFT
            }
        }

        // Update the previous position and time
        previousPinchX = currentPinchX
        previousTime = currentTime

        return isPinchMove
    }

    private fun getPinchMoveDirection(): PinchMoveDirection? {
        return pinchMoveDirection
    }

    private fun detectClosedHand(landmarks: NormalizedLandmarkList): Boolean {
        val wrist = landmarks.landmarkList[0]

        // Indices for finger tips and corresponding MCP joints
        val fingerTips = listOf(4, 8, 12, 16, 20)
        val fingerMCPs = listOf(2, 5, 9, 13, 17)

        var extendedFingers = 0

        for (i in fingerTips.indices) {
            val tip = landmarks.landmarkList[fingerTips[i]]
            val mcp = landmarks.landmarkList[fingerMCPs[i]]

            // If the distance from tip to wrist is greater than from MCP to wrist, the finger is extended
            if (distance(tip, wrist) > distance(mcp, wrist)) {
                extendedFingers++
            }
        }

        // If less than two fingers are extended, consider the hand closed
        return extendedFingers <= 1
    }

    private fun detectOpenHand(landmarks: NormalizedLandmarkList): Boolean {
        val wrist = landmarks.landmarkList[0]
    
        // Check if index finger is extended
        val indexTip = landmarks.landmarkList[8]
        val indexMCP = landmarks.landmarkList[5]
        val indexExtended = distance(indexTip, wrist) > distance(indexMCP, wrist)
    
        // Check if other fingers (excluding thumb) are closed
        var otherFingersClosed = true
        for (i in listOf(12, 16, 20)) { // middle, ring, pinky
            val tip = landmarks.landmarkList[i]
            val mcp = landmarks.landmarkList[i - 3] // Corresponding MCP joint
            if (distance(tip, wrist) > distance(mcp, wrist)) {
                otherFingersClosed = false
                break
            }
        }
    
        // Return true if index finger is extended and other fingers are closed
        return indexExtended && otherFingersClosed
    }

    private fun distance(a: LandmarkProto.NormalizedLandmark, b: LandmarkProto.NormalizedLandmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }


    private fun drawHandLandmarks(frame: Mat) {
        for (landmarks in handLandmarks) {
            // Draw lines connecting hand landmarks
            for (connection in HAND_CONNECTIONS) {
                val start = landmarks.landmarkList[connection.first]
                val end = landmarks.landmarkList[connection.second]
    
                val startPoint = org.opencv.core.Point(
                    (start.x * frame.cols()).toDouble(),
                    (start.y * frame.rows()).toDouble()
                )
                val endPoint = org.opencv.core.Point(
                    (end.x * frame.cols()).toDouble(),
                    (end.y * frame.rows()).toDouble()
                )
    
                Imgproc.line(
                    frame,
                    startPoint,
                    endPoint,
                    Scalar(0.0, 255.0, 0.0), // Green color
                    2 // Line thickness
                )
            }
        }
    }
    

}