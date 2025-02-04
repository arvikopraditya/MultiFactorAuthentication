package com.mfa.camerax

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.OnImageCapturedCallback
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.database.FirebaseDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.mfa.facedetector.MlKitAnalyzer
import com.mfa.utils.BitmapUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val previewView: PreviewView,
    private val graphicOverlay: GraphicOverlay<*>,
    private val lifecycleOwner: LifecycleOwner
) {

    private lateinit var imageAnalyzer: MlKitAnalyzer
    private lateinit var cameraProvider: ProcessCameraProvider

    //usecases
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var previewUseCase: Preview
    private lateinit var camera: Camera
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var cameraSelector: CameraSelector
    var flipX: Boolean = false

    fun cameraStart() {
        val cameraProcessProvider = ProcessCameraProvider.getInstance(context)
        //After requesting a CameraProvider, verify that its initialization succeeded when the view is created.
        cameraProcessProvider.addListener(
            {
                // Camera provider is now guaranteed to be available
                cameraProvider = cameraProcessProvider.get()

                // Choose the camera by requiring a lens facing
                cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(cameraOption)
                    .build()
                //clear prev usecase binding
                cameraProvider.unbindAll()

                bindPreviewUseCase()
                bindImageCaptureUseCase()
                bindImageAnalysisUseCase()
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun bindPreviewUseCase() {
        /*            camera = cameraProvider.bindToLifecycle(
                          lifecycleOwner,
                          cameraSelector,
                          previewUseCase,
                          imageAnalysis
                      )
                      previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
          */
        previewUseCase = Preview.Builder().build()
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, previewUseCase)
    }

    private fun bindImageCaptureUseCase() {
        val activity: Activity = context as Activity
        imageCapture = ImageCapture.Builder()
            .setTargetRotation(activity.windowManager.defaultDisplay.rotation)
            .build()
        camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)
    }

    private fun bindImageAnalysisUseCase() {
        imageAnalyzer = MlKitAnalyzer(graphicOverlay)
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, imageAnalyzer)
            }
        camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
    }

    fun onTakeImage(callback: OnTakeImageCallback) {
        imageCapture.takePicture(cameraExecutor, object : OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                val bitmap = getBitmapFromImageProxy(imageProxy)
                imageProxy.close()

                bitmap?.let { img ->
                    detectFace(img) { faces ->
                        faces.firstOrNull()?.let { face ->
                            val boundingBox = face.boundingBox
                            Log.d("CameraManager", "Bounding Box Registrasi: $boundingBox")

                            val adjustedBoundingBox = RectF(
                                boundingBox.left.toFloat().coerceAtLeast(0f),
                                boundingBox.top.toFloat().coerceAtLeast(0f),
                                boundingBox.right.toFloat().coerceAtMost(img.width.toFloat()),
                                boundingBox.bottom.toFloat().coerceAtMost(img.height.toFloat())
                            )

                            var croppedBitmap = BitmapUtils.getCropBitmapByCPU(img, adjustedBoundingBox)

                            if (cameraOption == CameraSelector.LENS_FACING_FRONT) {
                                croppedBitmap = flipBitmap(croppedBitmap)
                            }

                            croppedBitmap = Bitmap.createScaledBitmap(croppedBitmap, 256, 256, false)

                            Log.d("CameraManager", "Registrasi - Ukuran Cropped Face: ${croppedBitmap.width}x${croppedBitmap.height}")

                            callback.onTakeImageSuccess(croppedBitmap)
                        }
                    }
                }
            }
        })
    }


    private fun detectFace(bitmap: Bitmap, onFacesDetected: (List<Face>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                onFacesDetected(faces) // Kirim wajah yang terdeteksi ke callback
            }
            .addOnFailureListener { e ->
                Log.e("CameraManager", "Error saat deteksi wajah: ${e.message}")
            }
    }


    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun getBitmapFromImageProxy(imageProxy: ImageProxy): Bitmap? {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            Log.e("CameraManager", "Media image null!")
            imageProxy.close()
            return null
        }

        // Konversi dari ImageProxy ke Bitmap
        return BitmapUtils.getBitmap(imageProxy)
    }


    private fun flipBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun changeCamera() {
        cameraStop()
//        cameraOption = if (cameraOption == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
//        else CameraSelector.LENS_FACING_BACK
        if (cameraOption == CameraSelector.LENS_FACING_BACK) {
            cameraOption = CameraSelector.LENS_FACING_FRONT
            flipX = true
        } else {
            cameraOption = CameraSelector.LENS_FACING_BACK
            flipX = false
        }

        CameraUtils.toggleSelector()
        cameraStart()
    }

    fun cameraStop() {
        cameraProvider.unbindAll()
    }

    interface OnTakeImageCallback {
        fun onTakeImageSuccess(image : Bitmap)
        fun onTakeImageError(exception: ImageCaptureException)
    }

    companion object {
        private const val TAG: String = "CameraManager"
        var cameraOption: Int = CameraSelector.LENS_FACING_FRONT
    }
}