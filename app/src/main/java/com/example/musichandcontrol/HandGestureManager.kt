import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsOptions
import com.google.mediapipe.solutions.hands.HandsResult
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.common.collect.ImmutableList
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.media3.session.MediaController
import com.example.musichandcontrol.MediaControlNotificationListener
import com.google.mediapipe.formats.proto.LandmarkProto

class HandGestureManager(private val context: Context) {
    private lateinit var hands: Hands
    private var handLandmarks: ImmutableList<NormalizedLandmarkList> = ImmutableList.of()
    private var lastGestureTime: Long = 0
    private val GESTURE_COOLDOWN = 500 // ms cooldown between gestures

    private var previousPinchX: Float? = null
    private var previousTime: Long = 0
    private val PINCH_MOVE_THRESHOLD = 0.005f
    private var pinchMoveDirection: PinchMoveDirection? = null

    private enum class PinchMoveDirection { LEFT, RIGHT }

    fun initialize() {
        val options = HandsOptions.builder()
            .setStaticImageMode(false)
            .setMaxNumHands(2)
            .setRunOnGpu(true)
            .build()

        hands = Hands(context, options)

        hands.setResultListener { handsResult ->
            processHandTrackingResult(handsResult)
        }
    }

    fun processFrame(bitmap: Bitmap, timestamp: Long) {
        hands.send(bitmap, timestamp)
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

    private fun detectPinchAndMoveGesture(landmarks: NormalizedLandmarkList): Boolean {
        val thumb = landmarks.landmarkList[4]
        val index = landmarks.landmarkList[8]

        val isPinched = distance(thumb, index) < 0.05f

        if (!isPinched) {
            previousPinchX = null
            return false
        }

        val currentPinchX = (thumb.x + index.x) / 2
        val currentTime = System.currentTimeMillis()

        var isPinchMove = false

        if (previousPinchX != null && currentTime - previousTime < 500) {
            val deltaX = currentPinchX - previousPinchX!!

            if (abs(deltaX) > PINCH_MOVE_THRESHOLD) {
                isPinchMove = true
                pinchMoveDirection = if (deltaX < 0) PinchMoveDirection.RIGHT else PinchMoveDirection.LEFT
            }
        }

        previousPinchX = currentPinchX
        previousTime = currentTime

        return isPinchMove
    }

    private fun getPinchMoveDirection(): PinchMoveDirection? {
        return pinchMoveDirection
    }

    private fun detectClosedHand(landmarks: NormalizedLandmarkList): Boolean {
        val wrist = landmarks.landmarkList[0]
        val fingerTips = listOf(4, 8, 12, 16, 20)
        val fingerMCPs = listOf(2, 5, 9, 13, 17)

        var extendedFingers = 0

        for (i in fingerTips.indices) {
            val tip = landmarks.landmarkList[fingerTips[i]]
            val mcp = landmarks.landmarkList[fingerMCPs[i]]

            if (distance(tip, wrist) > distance(mcp, wrist)) {
                extendedFingers++
            }
        }

        return extendedFingers <= 1
    }

    private fun detectOpenHand(landmarks: NormalizedLandmarkList): Boolean {
        val wrist = landmarks.landmarkList[0]
        val indexTip = landmarks.landmarkList[8]
        val indexMCP = landmarks.landmarkList[5]
        val indexExtended = distance(indexTip, wrist) > distance(indexMCP, wrist)

        var otherFingersClosed = true
        for (i in listOf(12, 16, 20)) {
            val tip = landmarks.landmarkList[i]
            val mcp = landmarks.landmarkList[i - 3]
            if (distance(tip, wrist) > distance(mcp, wrist)) {
                otherFingersClosed = false
                break
            }
        }

        return indexExtended && otherFingersClosed
    }

    private fun distance(a: LandmarkProto.NormalizedLandmark, b: LandmarkProto.NormalizedLandmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun getHandLandmarks(): ImmutableList<NormalizedLandmarkList> {
        return handLandmarks
    }

    companion object {
        private const val TAG = "HandGestureManager"
    }
}