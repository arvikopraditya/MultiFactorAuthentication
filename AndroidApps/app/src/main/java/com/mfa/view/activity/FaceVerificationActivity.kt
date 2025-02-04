package com.mfa.view.activity

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.lifecycle.ViewModelProvider
import com.mfa.api.request.EmailRequest
import com.mfa.api.request.UpdateStatusReq
import com.mfa.camerax.CameraEkspresi
import com.mfa.camerax.CameraManager
import com.mfa.databinding.ActivityFaceVerificationBinding
import com.mfa.di.Injection
import com.mfa.facedetector.EkspresiRecognizer
import com.mfa.facedetector.FaceRecognizer
import com.mfa.utils.Utils
import com.mfa.`object`.Email
import com.mfa.`object`.IdJadwal
import com.mfa.view_model.ProfileViewModel
import com.mfa.view_model.ViewModelFactory
import kotlin.math.sqrt

class FaceVerificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFaceVerificationBinding
    private lateinit var profileViewModel: ProfileViewModel
    private lateinit var cameraEkspresi: CameraEkspresi
    private lateinit var ekspresiRecognizer: EkspresiRecognizer
    private lateinit var faceRecognizer: FaceRecognizer

    private val EMBEDDING_THRESHOLD = 0.75f
    private val expressionCommands = arrayOf("senyum", "kedip", "tutup mata kanan", "tutup mata kiri")
    private var currentCommandIndex = 0
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Verifikasi Wajah"

        faceRecognizer = FaceRecognizer(assets)
        profileViewModel = ViewModelProvider(this, ViewModelFactory(Injection.provideRepository(this))).get(ProfileViewModel::class.java)

        ekspresiRecognizer = EkspresiRecognizer { expression -> handleDetectedExpression(expression) }

        cameraEkspresi = CameraEkspresi(this, binding.previewView, this) { expression -> handleDetectedExpression(expression) }

        cameraEkspresi.cameraStart()
        updateExpressionText()
    }

    private fun updateExpressionText() {
        binding.expressionCommandText.text = "Lakukan: ${expressionCommands[currentCommandIndex]}"
    }

    fun handleDetectedExpression(expression: String) {
        if (isCapturing) return

        if (expression.equals(expressionCommands[currentCommandIndex], ignoreCase = true)) {
            isCapturing = true

            Log.d("FaceVerification", "Ekspresi cocok: $expression, mengambil gambar...")

            Handler(Looper.getMainLooper()).postDelayed({
                cameraEkspresi.onTakeImage(object : CameraEkspresi.OnTakeImageCallback {
                    override fun onTakeImageSuccess(bitmap: Bitmap?) {
                        if (bitmap == null) {
                            Log.e("FaceVerification", "Capture gagal: Bitmap is null")
                            isCapturing = false
                            return
                        }
                        Log.d("FaceVerification", "Verifikasi - Ukuran Gambar sebelum embedding: ${bitmap.width}x${bitmap.height}")
                        Log.d("FaceVerification", "Gambar berhasil diambil!")

                        runOnUiThread {
                            binding.imageViewPreview.setImageBitmap(bitmap)
                            binding.imageViewPreview.visibility = View.VISIBLE
                            binding.previewView.visibility = View.GONE
                            binding.verifyButton.visibility = View.VISIBLE
                            binding.verifyButton.setOnClickListener {
                                Log.d("FaceVerification", "Mulai verifikasi wajah...")
                                verifyFace(bitmap)
                            }
                        }
                    }

                    override fun onTakeImageError(exception: ImageCaptureException) {
                        Log.e("FaceVerification", "Capture gagal: ${exception.message}")
                        isCapturing = false
                        runOnUiThread {
                            Toast.makeText(this@FaceVerificationActivity, "Capture gagal, coba lagi!", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }, 500)
        }
    }

    private fun verifyFace(bitmap: Bitmap) {
        Log.d("FaceVerification", "Memulai verifikasi wajah...")

        var processedBitmap = bitmap

        // **Pastikan flipping dilakukan sama seperti saat registrasi**
        if (CameraManager.cameraOption == CameraSelector.LENS_FACING_FRONT) {
            processedBitmap = flipBitmap(processedBitmap)
        }

        processedBitmap = Bitmap.createScaledBitmap(processedBitmap, 256, 256, false)

        Log.d("FaceVerification", "Ukuran Gambar sebelum embedding: ${processedBitmap.width}x${processedBitmap.height}")

        try {
            val embeddingList = faceRecognizer.getEmbeddingsOfImage(processedBitmap)[0]
            if (embeddingList.isEmpty()) {
                Log.e("FaceVerification", "Gagal mendapatkan embedding wajah!")
                return
            }

            Log.d("FaceVerification", "Embedding wajah berhasil diperoleh")

            checkEmbeddings(embeddingList)
        } catch (e: Exception) {
            Log.e("FaceVerification", "Error saat ekstraksi embedding wajah: ${e.message}")
        }
    }


    private fun flipBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }



    private fun checkEmbeddings(embeddingList: FloatArray) {
        Log.d("FaceVerification", "Membandingkan embedding wajah dengan data di Firebase...")

        Utils.getFirebaseEmbedding().get().addOnSuccessListener { dataSnapshot ->
            val savedEmbedding = (dataSnapshot.value as? List<*>)?.mapNotNull { it.toString().toFloatOrNull() }?.toFloatArray()

            if (savedEmbedding != null && savedEmbedding.size == embeddingList.size) {
                // **Debugging: Tampilkan 5 nilai pertama dari setiap embedding untuk memastikan kecocokan**
                Log.d("FaceVerification", "Embedding User: ${embeddingList.take(5)}")
                Log.d("FaceVerification", "Embedding Firebase: ${savedEmbedding.take(5)}")

                val similarity = calculateCosineSimilarity(embeddingList, savedEmbedding)
                Log.d("FaceVerification", "Hasil Similarity: $similarity")

                if (similarity > EMBEDDING_THRESHOLD) {
                    Log.d("FaceVerification", "Wajah terverifikasi, lanjutkan presensi!")
                    proceedToAttendance()
                } else {
                    Log.e("FaceVerification", "Verifikasi wajah gagal! Similarity: $similarity")
                    runOnUiThread {
                        Toast.makeText(this, "Verifikasi wajah gagal, coba lagi!", Toast.LENGTH_LONG).show()
                    }
                    resetVerificationProcess()
                }
            } else {
                Log.e("FaceVerification", "Data embedding di Firebase tidak valid atau ukurannya tidak sama!")
            }
        }.addOnFailureListener {
            Log.e("FaceVerification", "Gagal mengambil data embedding dari Firebase: ${it.message}")
        }
    }


    private fun proceedToAttendance() {
        profileViewModel.getProfile(EmailRequest(Email.email))
        profileViewModel.getData.observe(this) {
            val nim = it.nim
            val data = UpdateStatusReq(IdJadwal.idJadwal, nim)
            profileViewModel.updateStatus(data)
            profileViewModel.getUpdateDataStatus.observe(this) { status ->
                if (status) {
                    runOnUiThread {
                        Toast.makeText(this, "Presensi berhasil!", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Presensi gagal!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun resetVerificationProcess() {
        isCapturing = false
        currentCommandIndex = 0
        updateExpressionText()
    }

    private fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        val normA = normalizeVector(vecA)
        val normB = normalizeVector(vecB)
        return normA.zip(normB).sumOf { (a, b) -> a * b.toDouble() }.toFloat()
    }

    private fun normalizeVector(vector: FloatArray): FloatArray {
        val magnitude = sqrt(vector.map { it * it }.sum().toDouble()).toFloat()
        return if (magnitude != 0f) vector.map { it / magnitude }.toFloatArray() else vector
    }



    override fun onDestroy() {
        super.onDestroy()
        Log.d("FaceVerification", "Menutup kamera...")
        cameraEkspresi.cameraStop()
    }
}
