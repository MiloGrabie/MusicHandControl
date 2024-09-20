package com.example.musichandcontrol

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.common.collect.ImmutableList
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsOptions
import com.google.mediapipe.solutions.hands.HandsResult
import kotlin.math.abs
import kotlin.math.sqrt

class HandGestureManager(
    private val context: Context,
    private val gestureListener: GestureListener
) {

    private lateinit var hands: Hands
    private var handLandmarks: ImmutableList<NormalizedLandmarkList> = ImmutableList.of()

    private var lastGestureTime: Long = 0
    private val GESTURE_COOLDOWN = 500 // ms cooldown between gestures

    private var previousPinchX: Float? = null
    private var previousTime: Long = 0
    private val PINCH_MOVE_THRESHOLD = 0.005f // Adjust this threshold based on testing
    private var pinchMoveDirection: PinchMoveDirection? = null

    private enum class PinchMoveDirection { LEFT, RIGHT }

    companion object {
        private const val TAG = "HandGestureManager"

        // Define hand connections
        val HAND_CONNECTIONS = listOf(
            Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
            Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
            Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
            Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
            Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
            Pair(5, 9), Pair(9, 13), Pair(13, 17)
        )
    }

    fun initializeHandTracking() {
        val options = HandsOptions.builder()
            .setStaticImageMode(false)
            .setMaxNumHands(2)
            .setRunOnGpu(true)
            .build()

        hands = Hands(context, options)

        hands.setResultListener { handsResult ->
            processHandTrackingResult(handsResult)
        }

        // Optionally, add an error listener
        // hands.addOnErrorListener { message, domain ->
        //     Log.e(TAG, "Error: $message, $domain")
        // }
    }

    fun sendFrame(bitmap: Bitmap, timestamp: Long) {
        hands.send(bitmap, timestamp)
    }

    fun release() {
        hands.close()
    }

    fun getHandLandmarks(): ImmutableList<NormalizedLandmarkList> {
        return handLandmarks
    }

    private fun processHandTrackingResult(result: HandsResult) {
        handLandmarks = result.multiHandLandmarks()
        val currentTime = System.currentTimeMillis()

        if (handLandmarks.isNotEmpty()) {
            val landmarks = handLandmarks[0]

            when {
                detectPinchAndMoveGesture(landmarks) -> {
                    val direction = getPinchMoveDirection()
                    if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                        if (direction == PinchMoveDirection.LEFT) {
                            Log.d(TAG, "Gesture detected: Pinch and Move Left")
                            gestureListener.onPinchMoveLeft()
                            lastGestureTime = currentTime
                        } else if (direction == PinchMoveDirection.RIGHT) {
                            Log.d(TAG, "Gesture detected: Pinch and Move Right")
                            gestureListener.onPinchMoveRight()
                            lastGestureTime = currentTime
                        }
                    }
                }
                detectClosedHand(landmarks) -> {
                    if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                        Log.d(TAG, "Gesture detected: Closed Hand")
                        gestureListener.onHandClosed()
                        lastGestureTime = currentTime
                    }
                }
                detectOpenHand(landmarks) -> {
                    if (currentTime - lastGestureTime > GESTURE_COOLDOWN) {
                        Log.d(TAG, "Gesture detected: Open Hand")
                        gestureListener.onHandOpened()
                        lastGestureTime = currentTime
                    }
                }
            }
        }
    }

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

    interface GestureListener {
        fun onPinchMoveLeft()
        fun onPinchMoveRight()
        fun onHandClosed()
        fun onHandOpened()
    }
}
