package com.example.filmsmartmeter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvAperture: TextView
    private lateinit var spinnerISO: Spinner
    private lateinit var spinnerShutter: Spinner
    private lateinit var cameraExecutor: ExecutorService

    // 니콘 FM2 사양 기반 데이터
    private val isoValues = listOf(12, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400)
    private val shutterLabels = listOf("1", "1/2", "1/4", "1/8", "1/15", "1/30", "1/60", "1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000")
    private val shutterSpeeds = listOf(1.0, 0.5, 0.25, 0.125, 0.066, 0.033, 0.016, 0.008, 0.004, 0.002, 0.001, 0.0005, 0.00025)

    private var selectedISO = 100.0
    private var selectedShutter = 0.008 // 1/125

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvAperture = findViewById(R.id.tvAperture)
        spinnerISO = findViewById(R.id.spinnerISO)
        spinnerShutter = findViewById(R.id.spinnerShutter)

        setupControls()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupControls() {
        // ISO 스피너 설정
        val isoAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, isoValues.map { "ISO $it" })
        isoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerISO.adapter = isoAdapter
        spinnerISO.setSelection(3) // 기본값 ISO 100

        // 셔터 스피드 스피너 설정
        val shutterAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, shutterLabels)
        shutterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerShutter.adapter = shutterAdapter
        spinnerShutter.setSelection(7) // 기본값 1/125

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedISO = isoValues[spinnerISO.selectedItemPosition].toDouble()
                selectedShutter = shutterSpeeds[spinnerShutter.selectedItemPosition]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerISO.onItemSelectedListener = listener
        spinnerShutter.onItemSelectedListener = listener
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val luma = calculateLuma(imageProxy)
                        runOnUiThread { updateAperture(luma) }
                        imageProxy.close()
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Toast.makeText(this, "Camera Error", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun calculateLuma(image: ImageProxy): Double {
        val buffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data.map { it.toInt() and 0xFF }.average()
    }

    private fun updateAperture(luma: Double) {
        // 실제 노출 공식 적용
        // 1. 센서 루마값을 EV값으로 변환 (보정 상수 12.0은 일반 스마트폰 센서 기준)
        val ev = log2(luma + 1.0) + 5.0

        // 2. 조리개 계산 공식: f-number = sqrt( (ISO * shutter * 2^EV) / 100 )
        // 또는 간단하게 N = sqrt( (t * 2^EV) / (100/ISO) )
        val aperture = sqrt(selectedShutter * 2.0.pow(ev) * (selectedISO / 100.0))

        if (aperture.isNaN() || aperture < 1.0) {
            tvAperture.text = "f/1.0 (Low Light)"
        } else if (aperture > 32) {
            tvAperture.text = "f/32 (Too Bright)"
        } else {
            // 소수점 한자리 반올림
            tvAperture.text = "f/${String.format("%.1f", aperture)}"
        }
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