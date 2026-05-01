package com.vidit.vcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.vidit.vcam.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var activeRecording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private val shutterSound = MediaActionSound()

    private var isFrontCamera = false
    private var isRecording = false
    private var isSheetOpen = false
    private var flashState = 0
    private var poseDetectionEnabled = true
    private var wasAligned = false
    private var lastHapticTime = 0L
    private var lastPoseTimestamp = 0L

    private lateinit var poseDetector: PoseDetector
    private lateinit var vibrator: Vibrator
    private var toneGenerator: ToneGenerator? = null

    enum class Mode { PHOTO, VIDEO, PORTRAIT, NIGHT, PRO, SLOWMO }
    private var currentMode = Mode.PHOTO

    private var recSeconds = 0
    private val recHandler = Handler(Looper.getMainLooper())
    private val zoomHandler = Handler(Looper.getMainLooper())
    private val flashHandler = Handler(Looper.getMainLooper())

    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startCamera()
        else Toast.makeText(this, "Camera permission zaroori hai!", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        try { 
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) 
        } catch (e: Exception) { 
            Log.e("VCam", "Tone init failed") 
        }

        if (allGranted()) startCamera() else permLauncher.launch(permissions)
        setupButtons()
        setupModes()
        setupSegments()
        applyProgrammaticUI()
    }

    private fun applyProgrammaticUI() {
        b.shutterBtn.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        val sheetBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xE6080808.toInt())
            cornerRadii = floatArrayOf(48f, 48f, 48f, 48f, 0f, 0f, 0f, 0f)
        }
        b.settingsSheet.background = sheetBg
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).also { f ->
            f.addListener({ 
                cameraProvider = f.get()
                bindCamera() 
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bindCamera() {
        val cp = cameraProvider ?: return
        val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.viewFinder.surfaceProvider) }
        
        imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )).build()
        videoCapture = VideoCapture.withOutput(recorder)
        
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { 
                it.setAnalyzer(cameraExecutor) { img -> processImageForPose(img) } 
            }

        try {
            cp.unbindAll()
            camera = cp.bindToLifecycle(this, selector, preview, imageCapture, videoCapture, imageAnalysis)
            setupPinchZoom()
            observeZoom()
        } catch (e: Exception) {
            Log.e("VCam", "Bind failed: ${e.message}")
            try {
                cp.unbindAll()
                camera = cp.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
                setupPinchZoom()
                observeZoom()
            } catch (e2: Exception) { 
                Log.e("VCam", "Retry failed: ${e2.message}") 
            }
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImageForPose(imageProxy: ImageProxy) {
        if (!poseDetectionEnabled) { 
            imageProxy.close()
            return 
        }
        val now = System.currentTimeMillis()
        if (now - lastPoseTimestamp < 100) { 
            imageProxy.close()
            return 
        }
        lastPoseTimestamp = now
        val mediaImage = imageProxy.image ?: run { 
            imageProxy.close()
            return 
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        
        poseDetector.process(inputImage)
            .addOnSuccessListener { pose ->
                runOnUiThread {
                    b.poseOverlay.updatePose(pose, imageProxy.width, imageProxy.height)
                    val score = b.poseOverlay.calculateAlignment()
                    val aligned = score >= 0.9f
                    b.poseOverlay.setAligned(aligned)
                    updateShutterGlow(aligned)
                    
                    if (aligned && !wasAligned) {
                        triggerAlignmentSignal()
                    }
                    wasAligned = aligned
                    
                    if (pose.allPoseLandmarks.isNotEmpty()) {
                        b.tvScene.text = if (aligned) "PERFECT ✓" else "ALIGN ${(score * 100).toInt()}%"
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun updateShutterGlow(aligned: Boolean) {
        if (isRecording) return
        b.shutterBtn.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (aligned) 0xFFD4AF37.toInt() else 0xFFFFFFFF.toInt())
            if (aligned) setStroke(4, 0xFFFFD700.toInt())
        }
    }

    private fun triggerAlignmentSignal() {
        val now = System.currentTimeMillis()
        if (now - lastHapticTime < 2000) return
        lastHapticTime = now
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), intArrayOf(0, 200, 0, 200), -1))
            } else {
                @Suppress("DEPRECATION") 
                vibrator.vibrate(longArrayOf(0, 80, 60, 80), -1)
            }
        } catch (e: Exception) { 
            Log.e("VCam", "Haptic failed") 
        }
        try { 
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) 
        } catch (e: Exception) {}
        pulseFlash()
    }

    private fun pulseFlash() {
        camera?.cameraControl?.enableTorch(true)
        flashHandler.postDelayed({ 
            camera?.cameraControl?.enableTorch(false)
            flashHandler.postDelayed({ 
                camera?.cameraControl?.enableTorch(true)
                flashHandler.postDelayed({ 
                    camera?.cameraControl?.enableTorch(false) 
                }, 100)
            }, 100)
        }, 100)
    }

    private fun setupPinchZoom() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val state = cam.cameraInfo.zoomState.value ?: return false
                val newZoom = (state.zoomRatio * det.scaleFactor).coerceIn(state.minZoomRatio, minOf(state.maxZoomRatio, 10f))
                cam.cameraControl.setZoomRatio(newZoom)
                showZoomBadge(newZoom)
                return true
            }
        })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { 
                tapFocus(e.x, e.y)
                return true 
            }
        })
        b.viewFinder.setOnTouchListener { v, event -> 
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            v.performClick()
            true 
        }
    }

    private fun observeZoom() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            updateZoomButtons(state.zoomRatio)
            b.tvUpscale.text = "AI ↑ ${String.format("%.1f", state.zoomRatio)}×"
        }
    }

    private fun showZoomBadge(zoom: Float) {
        b.zoomIndicator.text = "${String.format("%.1f", zoom)}×"
        b.zoomIndicator.visibility = View.VISIBLE
        zoomHandler.removeCallbacksAndMessages(null)
        zoomHandler.postDelayed({ b.zoomIndicator.visibility = View.GONE }, 1400)
    }

    private fun updateZoomButtons(zoom: Float) {
        mapOf(b.z06x to 0.6f, b.z1x to 1f, b.z2x to 2f, b.z5x to 5f, b.z10x to 10f).forEach { (tv, level) ->
            val active = abs(zoom - level) < 0.4f
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 28f
                setColor(if (active) 0x26FFFFFF.toInt() else 0x12FFFFFF.toInt())
                setStroke(1, if (active) 0x40FFFFFF.toInt() else 0x1AFFFFFF.toInt())
            }
            tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0x44FFFFFF.toInt())
        }
    }

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(state.minZoomRatio, minOf(state.maxZoomRatio, 10f)))
        showZoomBadge(ratio)
    }

    private fun tapFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val point = b.viewFinder.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        )
        b.focusRing.apply {
            this.x = x - width / 2f
            this.y = y - height / 2f
            alpha = 1f
            visibility = View.VISIBLE
            animate().alpha(0f).setDuration(700).setStartDelay(500).withEndAction { visibility = View.INVISIBLE }.start()
        }
    }

    private fun capturePhoto() {
        val ic = imageCapture ?: return
        b.viewFinder.animate().alpha(0.1f).setDuration(60).withEndAction { 
            b.viewFinder.animate().alpha(1f).setDuration(140).start() 
        }.start()
        b.shutterBtn.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70).withEndAction { 
            b.shutterBtn.animate().scaleX(1f).scaleY(1f).setDuration(100).start() 
        }.start()
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VCam_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/VCam")
        }
        
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) { 
                    Toast.makeText(this@MainActivity, "✓ Saved!", Toast.LENGTH_SHORT).show() 
                }
                override fun onError(e: ImageCaptureException) { 
                    Toast.makeText(this@MainActivity, "Error!", Toast.LENGTH_SHORT).show() 
                }
            }
        )
    }

    private fun toggleRecord() { 
        if (isRecording) stopRecord() else startRecord() 
    }

    private fun startRecord() {
        val vc = videoCapture ?: return
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VCam_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/VCam")
        }
        activeRecording = vc.output.prepareRecording(this,
            MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build())
            .withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { }
            
        isRecording = true
        b.recPill.visibility = View.VISIBLE
        b.shutterBtn.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 12f
            setColor(0xFFFF3B30.toInt())
        }
        recSeconds = 0
        
        val r = object : Runnable {
            override fun run() {
                recSeconds++
                b.tvRecTime.text = "${(recSeconds / 60).toString().padStart(2, '0')}:${(recSeconds % 60).toString().padStart(2, '0')}"
                if (isRecording) recHandler.postDelayed(this, 1000)
            }
        }
        recHandler.post(r)
    }

    private fun stopRecord() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        b.recPill.visibility = View.GONE
        b.shutterBtn.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        recHandler.removeCallbacksAndMessages(null)
        b.tvRecTime.text = "00:00"
        Toast.makeText(this, "✓ Video saved!", Toast.LENGTH_SHORT).show()
    }

    private fun setupButtons() {
        b.shutterBtn.setOnClickListener { 
            when (currentMode) { 
                Mode.VIDEO, Mode.SLOWMO -> toggleRecord()
                else -> capturePhoto() 
            } 
        }
        b.btnBack.setOnClickListener { 
            if (isSheetOpen) closeSheet() else onBackPressedDispatcher.onBackPressed() 
        }
        b.btnFlip.setOnClickListener { 
            if (isRecording) stopRecord()
            isFrontCamera = !isFrontCamera
            it.animate().rotationBy(180f).setDuration(280).start()
            bindCamera() 
        }
        b.btnFlash.setOnClickListener {
            flashState = (flashState + 1) % 3
            imageCapture?.flashMode = when (flashState) { 
                1 -> ImageCapture.FLASH_MODE_ON
                2 -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF 
            }
            Toast.makeText(this, listOf("Flash Off", "Flash On", "Flash Auto")[flashState], Toast.LENGTH_SHORT).show()
        }
        b.btnSettings.setOnClickListener { if (isSheetOpen) closeSheet() else openSheet() }
        b.btnCloseSheet.setOnClickListener { closeSheet() }
        b.btnGallery.setOnClickListener { 
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply { type = "image/*" }) 
        }
        b.z06x.setOnClickListener { setZoom(0.6f) }
        b.z1x.setOnClickListener { setZoom(1f) }
        b.z2x.setOnClickListener { setZoom(2f) }
        b.z5x.setOnClickListener { setZoom(5f) }
        b.z10x.setOnClickListener { setZoom(10f) }
        
        b.swAI.setOnCheckedChangeListener { _, on -> 
            poseDetectionEnabled = on
            b.poseOverlay.visibility = if (on) View.VISIBLE else View.GONE
            b.aiDot.alpha = if (on) 1f else 0.3f 
        }
        b.swGrid.setOnCheckedChangeListener { _, on -> 
            b.gridOverlay.visibility = if (on) View.VISIBLE else View.GONE 
        }
        b.rowAmoled.setOnClickListener { b.checkAmoled.alpha = 1f; b.checkWhite.alpha = 0.2f }
        b.rowWhite.setOnClickListener { b.checkAmoled.alpha = 0.2f; b.checkWhite.alpha = 1f }
    }

    private fun setupModes() {
        val tabs = listOf(b.mSlowmo to Mode.SLOWMO, b.mVideo to Mode.VIDEO, b.mPhoto to Mode.PHOTO, b.mPortrait to Mode.PORTRAIT, b.mNight to Mode.NIGHT, b.mPro to Mode.PRO)
        tabs.forEach { (tv, mode) ->
            tv.setOnClickListener {
                if (isRecording) stopRecord()
                currentMode = mode
                tabs.forEach { (t, _) -> 
                    t.background = null
                    t.setTextColor(0x44FFFFFF.toInt()) 
                }
                tv.setTextColor(0xFFFFFFFF.toInt())
                val isVideo = mode == Mode.VIDEO || mode == Mode.SLOWMO
                if (!isRecording) {
                    b.shutterBtn.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = if (isVideo) android.graphics.drawable.GradientDrawable.RECTANGLE else android.graphics.drawable.GradientDrawable.OVAL
                        cornerRadius = if (isVideo) 12f else 0f
                        setColor(if (isVideo) 0xFFFF3B30.toInt() else 0xFFFFFFFF.toInt())
                    }
                }
            
                b.tvScene.text = when (mode) { 
                    Mode.PHOTO -> "LANDSCAPE"
                    Mode.VIDEO -> "VIDEO"
                    Mode.PORTRAIT -> "FACE"
                    Mode.NIGHT -> "NIGHT"
                    Mode.PRO -> "PRO"
                    Mode.SLOWMO -> "SLOMO" 
                }
            }
        }
    }

    private fun setupSegments() {
        fun segGroup(views: List<TextView>) = views.forEach { tv -> 
            tv.setOnClickListener { 
                views.forEach { 
                    it.setBackgroundColor(0)
                    it.setTextColor(0x66FFFFFF.toInt()) 
                }
                tv.setBackgroundColor(0x26FFFFFF.toInt())
                tv.setTextColor(0xFFFFFFFF.toInt()) 
            } 
        }
        segGroup(listOf(b.fps30, b.fps60, b.fps120))
        segGroup(listOf(b.encH264, b.encHEVC))
    }

    private fun openSheet() {
        isSheetOpen = true
        b.settingsSheet.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.viewFinder.setRenderEffect(RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP))
        }
        b.settingsSheet.translationY = 1400f
        b.settingsSheet.animate().translationY(0f).setDuration(380).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    private fun closeSheet() {
        isSheetOpen = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.viewFinder.setRenderEffect(null)
        }
        b.settingsSheet.animate().translationY(1400f).setDuration(300)
            .setInterpolator(android.view.animation.AccelerateInterpolator(2f))
            .withEndAction { b.settingsSheet.visibility = View.GONE }.start()
    }

    private fun allGranted() = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onBackPressed() { 
        if (isSheetOpen) closeSheet() else super.onBackPressed() 
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        shutterSound.release()
        poseDetector.close()
        toneGenerator?.release()
        recHandler.removeCallbacksAndMessages(null)
        zoomHandler.removeCallbacksAndMessages(null)
        flashHandler.removeCallbacksAndMessages(null)
    }
}
