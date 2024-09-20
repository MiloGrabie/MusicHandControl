import android.content.Context
import android.content.pm.PackageManager
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.JavaCameraView
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap
import android.util.Log
import android.view.SurfaceView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.musichandcontrol.MainActivity
import org.opencv.android.Utils
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.common.collect.ImmutableList

class CameraManager(private val context: Context) : CameraBridgeViewBase.CvCameraViewListener2 {
    private lateinit var mOpenCvCameraView: JavaCameraView
    private lateinit var handGestureManager: HandGestureManager

    fun initialize(activity: MainActivity) {
        mOpenCvCameraView = JavaCameraView(context, CameraBridgeViewBase.CAMERA_ID_FRONT)
        mOpenCvCameraView.visibility = SurfaceView.VISIBLE
        mOpenCvCameraView.setCvCameraViewListener(this)


        handGestureManager = HandGestureManager(context)
        handGestureManager.initialize()
    }

    fun getCameraView(): JavaCameraView {
        return mOpenCvCameraView
    }

    fun enableCameraView() {
        mOpenCvCameraView.enableView()
        Log.d("CameraManager", "Camera view enabled")  // Add this line
    }

    fun disableCameraView() {
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

        // Process the image with HandGestureManager
        handGestureManager.processFrame(bitmap, timestamp)

        // Draw rectangles for detected hands
        drawHandLandmarks(frame, handGestureManager.getHandLandmarks())

        return frame
    }

    private fun drawHandLandmarks(frame: Mat, handLandmarks: ImmutableList<NormalizedLandmarkList>) {
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

    companion object {
        private const val TAG = "CameraManager"

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
}