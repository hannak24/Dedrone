package com.example.dedrone

import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraDevice.StateCallback
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface.ROTATION_0
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.dedrone.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.camera2.interop.Camera2Interop
import com.google.firebase.auth.FirebaseAuth


data class LocalCamera(
    val id: String,
    val facing: Int?,
    val characteristics:
    CameraCharacteristics
) {
    override fun toString(): String {
        val cameraType = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front Camera"
            CameraCharacteristics.LENS_FACING_BACK -> "Back Camera"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External Camera"
            else -> "Unknown"
        }
        return "$id: $cameraType"
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var cameraManager: CameraManager
    private lateinit var selectedLocalCamera: LocalCamera
    private lateinit var localCameras: MutableList<LocalCamera>
    private lateinit var camera: Camera
    private lateinit var bitmapBuffer: Bitmap
    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var preview: Preview
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetectorHelper: ObjectDetectorHelper2
    private lateinit var droneAlert: DroneAlert
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        auth = FirebaseAuth.getInstance()

        // Request camera permissions
        if (allPermissionsGranted()) {
            initDetection()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        droneAlert = DroneAlert(this)

        viewBinding.stopAlarm.setOnClickListener {
            droneAlert.stopAlarm()
        }

        viewBinding.modeButton.setOnClickListener {
            val newMode = droneAlert.changeMode()
            viewBinding.modeButton.text = "MODE: ${newMode.name}"
        }

    }

    private fun initDetection() {
        objectDetectorHelper = ObjectDetectorHelper2(
            context = this,
            objectDetectorListener = object : DetectorListener {
                override fun onInitialized() {
                    Log.d(TAG, "initDetection: onInitialized")
                    setUpCamera()
                }

                override fun onError(error: String) {
                    Log.d(TAG, "onError: $error")
                }

                override fun onResults(
                    results: List<BoundingBox>?,
                    inferenceTime: Long,
                    imageHeight: Int,
                    imageWidth: Int
                ) {
                    Log.d(TAG, "onResults: $inferenceTime")
                    onDrone(results)
                    droneAlert.onDrone(results)
                }

            })

        objectDetectorHelper.setupObjectDetector()
        viewBinding.seekbar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress / 100f
                objectDetectorHelper.setThreshold(threshold)
                viewBinding.sensitivityText.text = "Threshold: $threshold"
                Log.d(TAG, "new threshold: $threshold")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

        })
        viewBinding.seekbar.progress = (ObjectDetectorHelper2.CONFIDENCE_THRESHOLD * 100).toInt()
    }

    // Declare and bind preview, capture and analysis use cases
    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()
                startCameras()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun startCameras() {
        showAvailableCameras()
        // Build and bind the camera use cases
        bindCameraUseCases()
    }

    private fun showAvailableCameras() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList
        Log.d(TAG, "showAvailableCameras: ${cameraIds.contentToString()}")
        localCameras = mutableListOf()
        cameraIds.forEach { id ->
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(id)
            localCameras += LocalCamera(
                id,
                cameraCharacteristics.get(CameraCharacteristics.LENS_FACING),
                cameraCharacteristics
            )
        }

        val adapter = ArrayAdapter(this, R.layout.simple_spinner_item, localCameras)

        // Set the dropdown layout style for the Spinner
        adapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)

        // Set the adapter to the Spinner
        viewBinding.camerasSpinner.adapter = adapter

        // Set an item selection listener for the Spinner
        viewBinding.camerasSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    // Handle the selected item here
                    selectedLocalCamera = localCameras[position]
                    bindCameraUseCases()
                    // Do something with the selected item
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // Handle nothing selected, if needed
                }
            }

        selectedLocalCamera = localCameras[0]
    }


    @SuppressLint("MissingPermission")
    private fun bind() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cameraManager.openCamera(selectedLocalCamera.id, cameraExecutor, object : StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
//                    val mPreviewRequestBuilder: CaptureRequest.Builder
//                    mPreviewRequestBuilder =  camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
//                    //mPreviewRequestBuilder.addTarget(surface)
//                    val fpsRange: Range<Int> = Range(1, 1)
//                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
//                    mPreviewRequestBuilder.addTarget(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    TODO("Not yet implemented")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    TODO("Not yet implemented")
                }

            })
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        // CameraProvider
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector - makes assumption that we're only using the back camera
        val cameraSelector =
            CameraSelector.Builder().build()


        preview =
            Preview.Builder()
                .setTargetRotation(viewBinding.viewFinder.display.rotation)
                .setTargetRotation(ROTATION_0)
                .setMaxResolution(Size(1024, 1024))
                .build()

        val build =
            ImageAnalysis.Builder()

        val ext:Camera2Interop.Extender<*> = Camera2Interop.Extender(build)
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(8, 8))

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        val imageAnalyzer =
            build.setTargetRotation(viewBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setMaxResolution(Size(1024, 1024))
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        if (!::bitmapBuffer.isInitialized) {
                            // The image rotation and RGB image buffer are initialized only once
                            // the analyzer has started running
                            bitmapBuffer = Bitmap.createBitmap(
                                image.width,
                                image.height,
                                Bitmap.Config.ARGB_8888
                            )
                        }

                        detectObjects(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)


            // Attach the viewfinder's surface provider to preview use case
            preview.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
       
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectObjects(image: ImageProxy) {
        // Copy out RGB bits to the shared bitmap buffer
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }

        val imageRotation = image.imageInfo.rotationDegrees
        // Pass Bitmap and rotation to the object detector helper for processing and detection
        objectDetectorHelper.detect(bitmapBuffer, imageRotation)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer.targetRotation = viewBinding.viewFinder.display.rotation
    }

    private fun onDrone(
        results: List<BoundingBox>?
    ) {
        val previewView = viewBinding.viewFinder
        if (results.isNullOrEmpty()
        ) {
            previewView.overlay.clear()
            previewView.setOnTouchListener { _, _ -> false } //no-op
            return
        }

        val newResults = results.map {
            BoundingBox(
                x1 = it.x1 * previewView.width,
                y1 = it.y1 * previewView.width,
                x2 = it.x2 * previewView.width,
                y2 = it.y2 * previewView.width,
                cx = it.cx,
                cy = it.cy,
                w = it.w,
                h = it.h,
                cnf = it.cnf
            )
        }

        previewView.overlay.clear()
        newResults.forEach {
            val qrCodeDrawable = QrCodeDrawable(it)
            previewView.overlay.add(qrCodeDrawable)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraX-MLKit"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).toTypedArray()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initDetection()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}