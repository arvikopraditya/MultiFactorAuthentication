package com.mfa.view.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCaptureException
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.mfa.camerax.CameraManager
import com.mfa.databinding.ActivityCaptureFaceBinding
import com.mfa.databinding.DialogAddFaceBinding
import com.mfa.facedetector.FaceAntiSpoofing
import com.mfa.facedetector.FaceRecognizer
import java.io.ByteArrayOutputStream

class FaceProcessorActivity : AppCompatActivity(), CameraManager.OnTakeImageCallback {
    private val TAG = "RegisterFaceActivity"
    private lateinit var binding: ActivityCaptureFaceBinding
    private lateinit var cameraManager: CameraManager

    private lateinit var faceRecognizer: FaceRecognizer
    private lateinit var fas: FaceAntiSpoofing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureFaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        faceRecognizer = FaceRecognizer(assets)
        fas = FaceAntiSpoofing(assets)

        cameraManager = CameraManager(this, binding.viewCameraPreview, binding.viewGraphicOverlay, this)

        askCameraPermission()
        buttonClicks()
    }

    private fun buttonClicks() {
        binding.buttonTurnCamera.setOnClickListener {
            cameraManager.changeCamera()
        }
        binding.buttonStopCamera.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            cameraManager.onTakeImage(this)
        }
    }

    private fun askCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraManager.cameraStart()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 0)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            cameraManager.cameraStart()
        } else {
            Toast.makeText(this, "Camera Permission Denied!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTakeImageSuccess(image: Bitmap) {
        val addFaceBinding = DialogAddFaceBinding.inflate(layoutInflater)
        addFaceBinding.capturedFace.setImageBitmap(image)

        AlertDialog.Builder(this)
            .setView(addFaceBinding.root)
            .setTitle("Konfirmasi Wajah")
            .setPositiveButton("OK") { _, _ ->
                if (antiSpoofDetection(image)) {
                    val faceBitmap = Bitmap.createScaledBitmap(image, 256, 256, false) // **Pastikan ukurannya 256x256**

                    // **Simpan ke Firebase Storage**
                    saveBitmapToFirebase(faceBitmap)

                    val embeddings: Array<FloatArray> = faceRecognizer.getEmbeddingsOfImage(faceBitmap)

                    if (embeddings.isNotEmpty() && embeddings[0].isNotEmpty()) {
                        val embeddingFloatList = embeddings[0].map { it.toString() }
                        val intent = Intent().apply {
                            putStringArrayListExtra(EXTRA_FACE_EMBEDDING, ArrayList(embeddingFloatList))
                        }
                        setResult(RESULT_OK, intent)
                        finish()
                    } else {
                        Log.e(TAG, "Embedding wajah kosong atau gagal dihitung!")
                        Toast.makeText(this, "Gagal mendapatkan embedding wajah!", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal") { dialog, _ ->
                dialog.cancel()
                binding.progressBar.visibility = View.INVISIBLE
            }
            .show()
    }

    private fun saveBitmapToFirebase(bitmap: Bitmap) {
        try {
            val storageRef = FirebaseStorage.getInstance().reference.child("faces/user_face.jpg")
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos) // **Kompres gambar untuk menghindari error**
            val data = baos.toByteArray()

            storageRef.putBytes(data)
                .addOnSuccessListener {
                    Log.d(TAG, "Wajah berhasil disimpan di Firebase Storage")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Gagal menyimpan wajah ke Firebase: ${e.message}")
                }

            // **Simpan metadata di Firebase Realtime Database**
            val databaseRef = FirebaseDatabase.getInstance().getReference("faces/user_embedding")
            databaseRef.setValue("Image saved") // **Hanya menyimpan status agar tidak crash**
                .addOnSuccessListener {
                    Log.d(TAG, "Embedding berhasil disimpan di Firebase")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Gagal menyimpan embedding di Firebase: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error saat menyimpan bitmap ke Firebase: ${e.message}")
        }
    }

    private fun antiSpoofDetection(faceBitmap: Bitmap): Boolean {
        val laplaceScore: Int = fas.laplacian(faceBitmap)
        if (laplaceScore < FaceAntiSpoofing.LAPLACIAN_THRESHOLD) {
            Toast.makeText(this, "Gambar terlalu buram!", Toast.LENGTH_LONG).show()
            return false
        } else {
            val score = fas.antiSpoofing(faceBitmap)
            if (score < FaceAntiSpoofing.THRESHOLD) {
                return true
            }
            Toast.makeText(this, "Wajah terdeteksi sebagai spoof!", Toast.LENGTH_LONG).show()
            return false
        }
    }

    override fun onTakeImageError(exception: ImageCaptureException) {
        Toast.makeText(this, "Gagal mengambil gambar: ${exception.message}", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_FACE_EMBEDDING = "EXTRA_FACE_EMBEDDING"
    }
}
