package com.vidit.vcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.*
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.vidit.vcam.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private lateinit var poseDetector: PoseDetector
    private var isFetchingAI = false
    private val apiKey = "YOUR_API_KEY_HERE"

    enum class Mode { PHOTO, VIDEO, PRO }
    private var currentMode = Mode.PHOTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        poseDetector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        
        if (allPermissionsGranted()) startCamera()
        else registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { p -> p }) startCamera() }.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))

        setupButtons()
        setupModes()
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            cameraProvider = f.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cp = cameraProvider ?: return
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.viewFinder.surfaceProvider) }

        // QUALITY FIX: Lock to high-res
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.UHD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { it.setAnalyzer(Executors.newSingleThreadExecutor()) { img -> processPose(img) } }

        try {
            cp.unbindAll()
            camera = cp.bindToLifecycle(this, selector, preview, imageCapture, videoCapture, analysis)
            setupProModeLogic()
        } catch (e: Exception) { Log.e("VCam", "Bind fail") }
    }

    private fun processPose(image: ImageProxy) {
        val mediaImage = image.image ?: return
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        poseDetector.process(input)
            .addOnSuccessListener { p -> 
                runOnUiThread { 
                    b.poseOverlay.updatePose(p, image.width, image.height)
                    val score = b.poseOverlay.calculateAlignment()
                    b.poseOverlay.setAligned(score > 0.85f)
                } 
            }
            .addOnCompleteListener { image.close() }
    }

    private fun setupButtons() {
        b.shutterBtn.setOnClickListener { if (currentMode == Mode.VIDEO) toggleRecord() else capture() }
        b.btnFlip.setOnClickListener { /* Toggle Logic */ }
        
        // Gemini Long Press
        b.viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Long press logic manually or use GestureDetector
            }
            true
        }

        b.exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                // PRO MODE REAL FIX: Exposure adjustment
                val index = p - 10
                camera?.cameraControl?.setExposureCompensationIndex(index)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun setupModes() {
        val clickMap = mapOf(b.mPhoto to Mode.PHOTO, b.mVideo to Mode.VIDEO, b.mPro to Mode.PRO)
        clickMap.forEach { (view, mode) ->
            view.setOnClickListener {
                currentMode = mode
                b.videoOptions.visibility = if (mode == Mode.VIDEO) View.VISIBLE else View.GONE
                b.proOptions.visibility = if (mode == Mode.PRO) View.VISIBLE else View.GONE
                clickMap.keys.forEach { it.setTextColor(0x44FFFFFF.toInt()) }
                view.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    private fun capture() {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "VCam_${System.currentTimeMillis()}"); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg") }
        imageCapture?.takePicture(ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build(), ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(o: ImageCapture.OutputFileResults) { Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show() }
            override fun onError(e: ImageCaptureException) {}
        })
    }

    private fun toggleRecord() { /* Recording logic here */ }
    private fun setupProModeLogic() { /* Additional manual controls link */ }
    private fun openSheet() { b.settingsSheet.visibility = View.VISIBLE }
    private fun closeSheet() { b.settingsSheet.visibility = View.GONE }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
    private fun toggleRecord() {
        val vc = videoCapture ?: return
        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
            b.shutterBtn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
        } else {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "VCam_${System.currentTimeMillis()}")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/VCam")
            }
            activeRecording = vc.output.prepareRecording(this, 
                MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build())
                .withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { }
            isRecording = true
            b.shutterBtn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(0xFFFF3B30.toInt())
            }
        }
    }

    private fun setupProModeLogic() {
        // Exposure Compensation logic already in SeekBar listener
        b.proOptions.visibility = if (currentMode == Mode.PRO) View.VISIBLE else View.GONE
    }

    private fun openSheet() {
        b.settingsSheet.visibility = View.VISIBLE
        b.settingsSheet.translationY = 1000f
        b.settingsSheet.animate().translationY(0f).setDuration(300).start()
    }

    private fun closeSheet() {
        b.settingsSheet.animate().translationY(1000f).setDuration(300).withEndAction {
            b.settingsSheet.visibility = View.GONE
        }.start()
    }

    private fun allPermissionsGranted() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        poseDetector.close()
    }
}
