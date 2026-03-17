package com.example.filmsmartmeter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

// 카메라 모델 정보를 관리하는 데이터 클래스
data class CameraModel(
    val name: String,
    val isoValues: List<Int>,
    val shutterLabels: List<String>,
    val shutterSpeeds: List<Double>
)

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvAperture: TextView
    private lateinit var tvCameraModel: TextView
    private lateinit var spinnerISO: Spinner
    private lateinit var spinnerShutter: Spinner
    private lateinit var btnCameraSelect: ImageButton
    private lateinit var cameraExecutor: ExecutorService

    // 지원하는 카메라 모델 리스트 (FM2, M6, AE-1)
    private val cameraModels = listOf(
        CameraModel(
            "Nikon FM2",
            listOf(12, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400),
            listOf("1", "1/2", "1/4", "1/8", "1/15", "1/30", "1/60", "1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000"),
            listOf(1.0, 0.5, 0.25, 0.125, 0.066, 0.033, 0.016, 0.008, 0.004, 0.002, 0.001, 0.0005, 0.00025)
        ),
        CameraModel(
            "Leica M6",
            listOf(6, 12, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400),
            listOf("1", "1/2", "1/4", "1/8", "1/15", "1/30", "1/60", "1/125", "1/250", "1/500", "1/1000"),
            listOf(1.0, 0.5, 0.25, 0.125, 0.066, 0.033, 0.016, 0.008, 0.004, 0.002, 0.001)
        ),
        CameraModel(
            "Canon AE-1",
            listOf(25, 50, 100, 200, 400, 800, 1600, 3200),
            listOf("2", "1", "1/2", "1/4", "1/8", "1/15", "1/30", "1/60", "1/125", "1/250", "1/500", "1/1000"),
            listOf(2.0, 1.0, 0.5, 0.25, 0.125, 0.066, 0.033, 0.016, 0.008, 0.004, 0.002, 0.001)
        )
    )

    private var currentModel = cameraModels[0] // 기본값: Nikon FM2
    private var selectedISO = 100.0
    private var selectedShutter = 0.008 // 1/125

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // XML 뷰 바인딩
        viewFinder = findViewById(R.id.viewFinder)
        tvAperture = findViewById(R.id.tvAperture)
        tvCameraModel = findViewById(R.id.tvCameraModel)
        spinnerISO = findViewById(R.id.spinnerISO)
        spinnerShutter = findViewById(R.id.spinnerShutter)
        btnCameraSelect = findViewById(R.id.btnCameraSelect)

        // 초기 컨트롤 설정
        setupControls()

        // 카메라 변경 버튼 클릭 리스너
        btnCameraSelect.setOnClickListener {
            showCameraSelectionDialog()
        }

        // 권한 확인 및 카메라 시작
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupControls() {
        tvCameraModel.text = "Model: ${currentModel.name}"

        // ISO 스피너 설정
        val isoAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, currentModel.isoValues.map { "ISO $it" })
        isoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerISO.adapter = isoAdapter

        // 기존 선택된 ISO 유지 시도 (없으면 100 또는 첫 번째 값)
        val isoIndex = currentModel.isoValues.indexOf(100).takeIf { it != -1 } ?: 0
        spinnerISO.setSelection(isoIndex)

        // 셔터 스피드 스피너 설정
        val shutterAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, currentModel.shutterLabels)
        shutterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerShutter.adapter = shutterAdapter

        val shutterIndex = currentModel.shutterLabels.indexOf("1/125").takeIf { it != -1 } ?: 0
        spinnerShutter.setSelection(shutterIndex)

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedISO = currentModel.isoValues[spinnerISO.selectedItemPosition].toDouble()
                selectedShutter = currentModel.shutterSpeeds[spinnerShutter.selectedItemPosition]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerISO.onItemSelectedListener = listener
        spinnerShutter.onItemSelectedListener = listener
    }

    private fun showCameraSelectionDialog() {
        val modelNames = cameraModels.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Camera")
            .setItems(modelNames) { _, which ->
                currentModel = cameraModels[which]
                setupControls() // 바뀐 기종에 맞춰 스피너 갱신
                Toast.makeText(this, "${currentModel.name} Selected", Toast.LENGTH_SHORT).show()
            }
            .show()
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
                Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show()
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
        val ev = log2(luma + 1.0) + 5.0
        val aperture = sqrt(selectedShutter * 2.0.pow(ev) * (selectedISO / 100.0))

        if (aperture.isNaN() || aperture < 1.0) {
            tvAperture.text = "f/1.0 (Dark)"
        } else if (aperture > 32) {
            tvAperture.text = "f/32 (Too bright)"
        } else {
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