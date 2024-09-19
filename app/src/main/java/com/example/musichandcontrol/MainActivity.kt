package com.example.musichandcontrol

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
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

import com.google.mediapipe.framework.AndroidPacketCreator
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsOptions
import com.google.mediapipe.solutions.hands.HandsResult
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.framework.PacketCreator
import com.google.mlkit.vision.common.InputImage
import org.opencv.android.Utils

class MainActivity : CameraActivity(), CameraBridgeViewBase.CvCameraViewListener2 {
    private lateinit var mOpenCvCameraView: JavaCameraView    
    private var handLandmarks: ImmutableList<NormalizedLandmarkList> = ImmutableList.of()
    private lateinit var hands: Hands

    override fun getCameraViewList(): List<CameraBridgeViewBase> {
        return listOf(mOpenCvCameraView)
    }
    
    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_REQUEST = 1
        
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

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}