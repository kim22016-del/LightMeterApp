package com.example.filmsmartmeter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvShutterSpeed: TextView
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvShutterSpeed = findViewById(R.id.tvShutterSpeed)

        // 1. 카메라 권한 확인 및 요청
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 프리뷰 설정
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // 이미지 분석: 빛의 밝기 측정 로직 [cite: 10]
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val luma = calculateLuma(imageProxy)
                        runOnUiThread {
                            updateExposureUI(luma)
                        }
                        imageProxy.close()
                    }
                }

            try {
                cameraProvider.unbindAll()
                // Pixel 7 및 다른 안드로이드 폰에서도 호환되도록 생명주기에 바인딩
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Toast.makeText(this, "카메라 초기화 실패", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 이미지에서 평균 밝기 추출
    private fun calculateLuma(image: ImageProxy): Double {
        val buffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val pixels = data.map { it.toInt() and 0xFF }
        return pixels.average()
    }

    // 밝기에 따른 셔터스피드 추천 로직 [cite: 10]
    private fun updateExposureUI(luma: Double) {
        val recommendation = when {
            luma > 200 -> "1/1000"
            luma > 160 -> "1/500"
            luma > 120 -> "1/250"
            luma > 80  -> "1/125"
            luma > 40  -> "1/60"
            else       -> "1/30"
        }
        tvShutterSpeed.text = "Shutter Speed: $recommendation"
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}