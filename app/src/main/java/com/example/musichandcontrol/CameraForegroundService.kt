package com.example.musichandcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.common.collect.ImmutableList
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsResult
import android.media.ImageReader
import android.media.Image
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Looper
import com.google.mediapipe.solutions.hands.HandsOptions
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class CameraForegroundService : Service() {

    companion object {
        private const val TAG = "CameraForegroundService"
        private const val CHANNEL_ID = "HandGestureServiceChannel"
        private const val NOTIFICATION_ID = 1

        // Define hand connections
        private val HAND_CONNECTIONS = listOf(
            Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
            Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
            Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
            Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
            Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
            Pair(5, 9), Pair(9, 13), Pair(13, 17)
        )
        const val ACTION_RESTART_SERVICE = "ACTION_RESTART_SERVICE"
        private const val CHECK_INTERVAL = 5000L // Check every 60 seconds

    }

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var cameraDevice: CameraDevice
    private lateinit var hands: Hands
    private var handLandmarks: ImmutableList<NormalizedLandmarkList> = ImmutableList.of()
    private var lastGestureTime: Long = 0
    private val GESTURE_COOLDOWN = 1500 // ms cooldown between gestures
    private var mediaController: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val checkMediaControllerRunnable = object : Runnable {
        override fun run() {
            checkAndReinitializeMediaController()
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        initializeCamera()
        initializeMediaPipe()
        initializeMediaController()
        handler.post(checkMediaControllerRunnable)
        //startForegroundService()
    }

    private fun checkAndReinitializeMediaController() {
        if (mediaController == null) {
            Log.d(TAG, "MediaController is null, attempting to reinitialize")
            initializeMediaController()
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundService() {
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hand Gesture Service")
            .setContentText("Controlling media playback with hand gestures")
            .setSmallIcon(R.drawable.baseline_waving_hand_24) // Replace with your app's icon
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors du démarrage du service en avant-plan", e)
        }

    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART_SERVICE) {
            Log.d(TAG, "Restarting service")
            stopSelf()
            startForegroundService()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Hand Gesture Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun initializeCamera() {
        cameraThread = HandlerThread("CameraThread")
        cameraThread.start()
        cameraHandler = Handler(cameraThread.looper)

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.first { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted")
                // Handle permission not granted scenario
                stopSelf()
                return
            }
            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            try{
                cameraDevice = camera
                startCaptureSession()
            }
            catch (e: Exception){
                Log.e(TAG, "Erreur lors de l'ouverture de la caméra", e)
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            stopSelf()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            stopSelf()
        }
    }

    private fun startCaptureSession() {
        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        try {
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    processFrame(bitmap)
                    image.close()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(imageReader.surface)
            cameraDevice.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        stopSelf()
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        // Y Plane
        image.planes[0].buffer.get(nv21, 0, ySize)

        // U and V Planes
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val pixelStride = image.planes[2].pixelStride
        val rowStride = image.planes[2].rowStride

        var uvPos = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                nv21[uvPos++] = vBuffer.get(row * rowStride + col * pixelStride)
                nv21[uvPos++] = uBuffer.get(row * rowStride + col * pixelStride)
            }
        }

        return nv21
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun initializeMediaPipe() {
        val options = HandsOptions.builder()
            .setStaticImageMode(false)
            .setMaxNumHands(2)
            .setRunOnGpu(true)
            .build()

        hands = Hands(this, options)
        hands.setResultListener { handsResult ->
            processHandTrackingResult(handsResult)
        }
    }


    private fun initializeMediaController() {
        val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, MediaControlNotificationListener::class.java)
        try {
            val controllers = mediaSessionManager.getActiveSessions(componentName)
            if (controllers.isNotEmpty()) {
                mediaController = controllers[0]
                Log.d(TAG, "MediaController reinitialized successfully")
            } else {
                Log.w(TAG, "No active media sessions found")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when initializing MediaController", e)
        }
    }


    private fun processFrame(bitmap: Bitmap) {
        try {
            val timestamp = System.nanoTime() / 1000
            hands.send(bitmap, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }

    private fun processHandTrackingResult(result: HandsResult) {
        try {
            handLandmarks = result.multiHandLandmarks()
            if (handLandmarks.isNotEmpty()) {
                val currentTime = System.currentTimeMillis()
                val landmarks = handLandmarks[0]

                // Add distance check
                if (isHandTooFar(landmarks)) {
                    Log.d(TAG, "Hand is too far from the camera")
                    return
                }

                when {
                    detectTwoFingerUp(landmarks) -> {
                        if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                            Log.d(TAG, "Gesture detected: Pinch")
                            mediaController?.transportControls?.skipToNext()
                            lastGestureTime = currentTime
                        }
                    }
                    detectClosedHand(landmarks) -> {
                        if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                            Log.d(TAG, "Gesture detected: Closed Hand")
                            mediaController?.transportControls?.pause()
                            lastGestureTime = currentTime
                        }
                    }
                    detectOpenHand(landmarks) -> {
                        if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                            Log.d(TAG, "Gesture detected: Open Hand")
                            mediaController?.transportControls?.play()
                            lastGestureTime = currentTime
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing hand tracking result", e)
        }
    }


    private fun isHandTooFar(landmarks: NormalizedLandmarkList): Boolean {
        val wrist = landmarks.landmarkList[0]
        val middleFinger = landmarks.landmarkList[12]

        // Calculate the distance between wrist and middle finger tip
        val distance = distance(wrist, middleFinger)

        // You may need to adjust this threshold based on testing
        val TOO_FAR_THRESHOLD = 0.15f

        return distance < TOO_FAR_THRESHOLD
    }

    private var twoFingerUpStartTime: Long = 0
    private val TWO_FINGER_UP_THRESHOLD = 500 // 0.5 seconds in milliseconds

    private fun detectTwoFingerUp(landmarks: NormalizedLandmarkList): Boolean {
        val wrist = landmarks.landmarkList[0]
        val indexTip = landmarks.landmarkList[8]
        val middleTip = landmarks.landmarkList[12]
        val ringTip = landmarks.landmarkList[16]
        val pinkyTip = landmarks.landmarkList[20]

        val indexMCP = landmarks.landmarkList[5]
        val middleMCP = landmarks.landmarkList[9]

        // Check if index and middle fingers are extended
        val indexExtended = distance(indexTip, wrist) > distance(indexMCP, wrist)
        val middleExtended = distance(middleTip, wrist) > distance(middleMCP, wrist)

        // Check if ring and pinky fingers are not extended
        val ringNotExtended = distance(ringTip, wrist) <= distance(landmarks.landmarkList[13], wrist)
        val pinkyNotExtended = distance(pinkyTip, wrist) <= distance(landmarks.landmarkList[17], wrist)

        val isTwoFingerUp = indexExtended && middleExtended && ringNotExtended && pinkyNotExtended

        val currentTime = System.currentTimeMillis()
        if (isTwoFingerUp) {
            if (twoFingerUpStartTime == 0L) {
                twoFingerUpStartTime = currentTime
            } else if (currentTime - twoFingerUpStartTime >= TWO_FINGER_UP_THRESHOLD) {
                twoFingerUpStartTime = 0L // Reset the timer after a successful two finger up gesture
                return true
            }
        } else {
            twoFingerUpStartTime = 0L
        }

        return false
    }

    private var pinchStartTime: Long = 0
    private val PINCH_THRESHOLD = 500 // 1 second in milliseconds

    private fun detectPinch(landmarks: NormalizedLandmarkList): Boolean {
        val thumb = landmarks.landmarkList[4]
        val index = landmarks.landmarkList[8]

        // Check if thumb and index are close (pinched)
        val isPinched = distance(thumb, index) < 0.05f // Adjust this threshold as needed

        val currentTime = System.currentTimeMillis()
        if (isPinched) {
            if (pinchStartTime == 0L) {
                pinchStartTime = currentTime
            } else if (currentTime - pinchStartTime >= PINCH_THRESHOLD) {
                pinchStartTime = 0L // Reset the timer after a successful pinch
                return true
            }
        } else {
            pinchStartTime = 0L
        }

        return false
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

    private var openHandStartTime: Long = 0
    private val OPEN_HAND_THRESHOLD = 0 // 1 second in milliseconds

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

        // Add pinch ignorer
        val thumb = landmarks.landmarkList[4]
        val isPinched = distance(thumb, indexTip) < 0.05f // Adjust this threshold as needed

        val isOpenHand = indexExtended && otherFingersClosed && !isPinched

        val currentTime = System.currentTimeMillis()
        if (isOpenHand) {
            if (openHandStartTime == 0L) {
                openHandStartTime = currentTime
            } else if (currentTime - openHandStartTime >= OPEN_HAND_THRESHOLD) {
                openHandStartTime = 0L // Reset the timer after a successful open hand gesture
                return true
            }
        } else {
            openHandStartTime = 0L
        }

        return false
    }

    private fun distance(a: LandmarkProto.NormalizedLandmark, b: LandmarkProto.NormalizedLandmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }


    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkMediaControllerRunnable)
        // Clean up resources
        cameraDevice.close()
        cameraThread.quitSafely()
        hands.close()

        // Restart the service if the app is still running
        val intent = Intent(applicationContext, CameraForegroundService::class.java)
        intent.action = ACTION_RESTART_SERVICE
        applicationContext.startService(intent)
    }

}
