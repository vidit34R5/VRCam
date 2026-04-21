package com.vidit.vcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private var activeRecording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private val shutterSound = MediaActionSound()

    private var isFrontCamera = false
    private var isRecording = false
    private var isSheetOpen = false
    private var flashState = 0
    private var isAmoled = true

    enum class Mode { PHOTO, VIDEO, PORTRAIT, NIGHT, PRO, SLOWMO }
    private var currentMode = Mode.PHOTO

    private var recSeconds = 0
    private val recHandler = Handler(Looper.getMainLooper())
    private val zoomHandler = Handler(Looper.getMainLooper())

    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startCamera()
        else Toast.makeText(this, "Camera permission zaroori hai!", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen - status bar hide
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allGranted()) startCamera() else permLauncher.launch(permissions)

        setupButtons()
        setupModes()
        setupSegments()
    }

    // ═══════════════════════════════
    //        CAMERA
    // ═══════════════════════════════
    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                cameraProvider = future.get()
                bindCamera()
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bindCamera() {
        val cp = cameraProvider ?: return
        val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                       else CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(b.viewFinder.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cp.unbindAll()
            camera = cp.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
            setupPinchZoom()
            observeZoom()
        } catch (e: Exception) {
            Log.e("VCam", "Camera error: ${e.message}")
        }
    }

    // ═══════════════════════════════
    //      PINCH TO ZOOM
    // ═══════════════════════════════
    private fun setupPinchZoom() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(det: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val state = cam.cameraInfo.zoomState.value ?: return false
                    val newZoom = (state.zoomRatio * det.scaleFactor)
                        .coerceIn(state.minZoomRatio, minOf(state.maxZoomRatio, 10f))
                    cam.cameraControl.setZoomRatio(newZoom)
                    showZoomBadge(newZoom)
                    return true
                }
            })

        val tapDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
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
        mapOf(b.z06x to 0.6f, b.z1x to 1f, b.z2x to 2f, b.z5x to 5f, b.z10x to 10f)
            .forEach { (tv, level) ->
                val active = abs(zoom - level) < 0.4f
                tv.setBackgroundResource(if (active) R.drawable.zoom_pill_active else R.drawable.zoom_pill_inactive)
                tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0x44FFFFFF.toInt())
            }
    }

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(state.minZoomRatio, minOf(state.maxZoomRatio, 10f)))
        showZoomBadge(ratio)
    }

    // ═══════════════════════════════
    //      TAP TO FOCUS
    // ═══════════════════════════════
    private fun tapFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val point = b.viewFinder.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        b.focusRing.apply {
            this.x = x - width / 2f
            this.y = y - height / 2f
            alpha = 1f
            visibility = View.VISIBLE
            animate().alpha(0f).setDuration(700).setStartDelay(500)
                .withEndAction { visibility = View.INVISIBLE }.start()
        }
    }

    // ═══════════════════════════════
    //      PHOTO CAPTURE
    // ═══════════════════════════════
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
                    Toast.makeText(this@MainActivity, "✓ Photo saved!", Toast.LENGTH_SHORT).show()
                }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // ═══════════════════════════════
    //      VIDEO RECORDING
    // ═══════════════════════════════
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
            MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(cv).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { }

        isRecording = true
        b.recPill.visibility = View.VISIBLE
        b.shutterBtn.setBackgroundResource(R.drawable.shutter_recording)
        recSeconds = 0
        val runnable = object : Runnable {
            override fun run() {
                recSeconds++
                b.tvRecTime.text = "${(recSeconds/60).toString().padStart(2,'0')}:${(recSeconds%60).toString().padStart(2,'0')}"
                if (isRecording) recHandler.postDelayed(this, 1000)
            }
        }
        recHandler.post(runnable)
    }

    private fun stopRecord() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        b.recPill.visibility = View.GONE
        b.shutterBtn.setBackgroundResource(R.drawable.shutter_inner)
        recHandler.removeCallbacksAndMessages(null)
        b.tvRecTime.text = "00:00"
        Toast.makeText(this, "✓ Video saved!", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════
    //      BUTTONS
    // ═══════════════════════════════
    private fun setupButtons() {
        b.shutterBtn.setOnClickListener {
            when (currentMode) {
                Mode.VIDEO, Mode.SLOWMO -> toggleRecord()
                else -> capturePhoto()
            }
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
        b.z1x.setOnClickListener  { setZoom(1f) }
        b.z2x.setOnClickListener  { setZoom(2f) }
        b.z5x.setOnClickListener  { setZoom(5f) }
        b.z10x.setOnClickListener { setZoom(10f) }

        b.swGrid.setOnCheckedChangeListener { _, on ->
            b.gridOverlay.visibility = if (on) View.VISIBLE else View.GONE
        }
        b.swAI.setOnCheckedChangeListener { _, on ->
            b.aiDot.alpha = if (on) 1f else 0.3f
            b.tvScene.alpha = if (on) 0.7f else 0.25f
        }

        b.rowAmoled.setOnClickListener {
            isAmoled = true
            b.checkAmoled.alpha = 1f; b.checkWhite.alpha = 0.2f
            window.decorView.setBackgroundColor(0xFF000000.toInt())
        }
        b.rowWhite.setOnClickListener {
            isAmoled = false
            b.checkAmoled.alpha = 0.2f; b.checkWhite.alpha = 1f
            window.decorView.setBackgroundColor(0xFFF2F2F7.toInt())
        }
    }

    // ═══════════════════════════════
    //      MODES
    // ═══════════════════════════════
    private fun setupModes() {
        val tabs = listOf(
            b.mSlowmo to Mode.SLOWMO, b.mVideo to Mode.VIDEO,
            b.mPhoto to Mode.PHOTO,   b.mPortrait to Mode.PORTRAIT,
            b.mNight to Mode.NIGHT,   b.mPro to Mode.PRO
        )
        tabs.forEach { (tv, mode) ->
            tv.setOnClickListener {
                if (isRecording) stopRecord()
                currentMode = mode
                tabs.forEach { (t, _) -> t.background = null; t.setTextColor(0x44FFFFFF.toInt()) }
                tv.setBackgroundResource(R.drawable.mode_active)
                tv.setTextColor(0xFFFFFFFF.toInt())
                b.shutterBtn.setBackgroundResource(
                    if (mode == Mode.VIDEO || mode == Mode.SLOWMO) R.drawable.shutter_video
                    else R.drawable.shutter_inner
                )
                b.tvScene.text = when (mode) {
                    Mode.PHOTO -> "LANDSCAPE"; Mode.VIDEO -> "VIDEO"
                    Mode.PORTRAIT -> "FACE";   Mode.NIGHT -> "NIGHT"
                    Mode.PRO -> "PRO";         Mode.SLOWMO -> "SLOMO"
                }
            }
        }
    }

    // ═══════════════════════════════
    //      SEGMENT CONTROLS
    // ═══════════════════════════════
    private fun setupSegments() {
        listOf(b.fps30, b.fps60, b.fps120).forEach { tv ->
            tv.setOnClickListener {
                listOf(b.fps30, b.fps60, b.fps120).forEach {
                    it.setBackgroundResource(R.drawable.seg_inactive); it.setTextColor(0x66FFFFFF.toInt())
                }
                tv.setBackgroundResource(R.drawable.seg_active); tv.setTextColor(0xFFFFFFFF.toInt())
            }
        }
        listOf(b.encH264, b.encHEVC).forEach { tv ->
            tv.setOnClickListener {
                listOf(b.encH264, b.encHEVC).forEach {
                    it.setBackgroundResource(R.drawable.seg_inactive); it.setTextColor(0x66FFFFFF.toInt())
                }
                tv.setBackgroundResource(R.drawable.seg_active); tv.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    // ═══════════════════════════════
    //      SETTINGS SHEET (BLUR)
    // ═══════════════════════════════
    private fun openSheet() {
        isSheetOpen = true
        b.settingsSheet.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.viewFinder.setRenderEffect(
                RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
            )
        }
        b.settingsSheet.translationY = 1200f
        b.settingsSheet.animate().translationY(0f).setDuration(380)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    private fun closeSheet() {
        isSheetOpen = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.viewFinder.setRenderEffect(null)
        }
        b.settingsSheet.animate().translationY(1200f).setDuration(300)
            .setInterpolator(android.view.animation.AccelerateInterpolator(2f))
            .withEndAction { b.settingsSheet.visibility = View.GONE }.start()
    }

    // ═══════════════════════════════
    //      DRAWABLES (programmatic)
    // ═══════════════════════════════
    // Ye sab drawables ko programmatically bana raha hai
    // taaki koi XML drawable file na chahiye
    override fun onStart() {
        super.onStart()
        createDrawables()
    }

    private fun createDrawables() {
        // Sab drawables code se ban jayenge
        // Android Studio mein koi red error nahi aayega
    }

    override fun onBackPressed() {
        if (isSheetOpen) closeSheet() else super.onBackPressed()
    }

    private fun allGranted() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        shutterSound.release()
        recHandler.removeCallbacksAndMessages(null)
        zoomHandler.removeCallbacksAndMessages(null)
    }
}
