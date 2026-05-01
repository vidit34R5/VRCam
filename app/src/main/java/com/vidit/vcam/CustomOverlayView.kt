package com.vidit.vcam

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs

class CustomOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pose: Pose? = null
    private var imageWidth = 1
    private var imageHeight = 1
    private var isAligned = false

    // Ghost pose target points (normalized 0-1 coords) - standing portrait pose
    private val ghostPose = mapOf(
        PoseLandmark.NOSE to PointF(0.5f, 0.12f),
        PoseLandmark.LEFT_SHOULDER to PointF(0.38f, 0.28f),
        PoseLandmark.RIGHT_SHOULDER to PointF(0.62f, 0.28f),
        PoseLandmark.LEFT_ELBOW to PointF(0.28f, 0.42f),
        PoseLandmark.RIGHT_ELBOW to PointF(0.72f, 0.42f),
        PoseLandmark.LEFT_WRIST to PointF(0.22f, 0.55f),
        PoseLandmark.RIGHT_WRIST to PointF(0.78f, 0.55f),
        PoseLandmark.LEFT_HIP to PointF(0.40f, 0.55f),
        PoseLandmark.RIGHT_HIP to PointF(0.60f, 0.55f),
        PoseLandmark.LEFT_KNEE to PointF(0.38f, 0.72f),
        PoseLandmark.RIGHT_KNEE to PointF(0.62f, 0.72f),
        PoseLandmark.LEFT_ANKLE to PointF(0.36f, 0.88f),
        PoseLandmark.RIGHT_ANKLE to PointF(0.64f, 0.88f)
    )

    private val connections = listOf(
        Pair(PoseLandmark.NOSE, PoseLandmark.LEFT_SHOULDER),
        Pair(PoseLandmark.NOSE, PoseLandmark.RIGHT_SHOULDER),
        Pair(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
        Pair(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW),
        Pair(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW),
        Pair(PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST),
        Pair(PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST),
        Pair(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP),
        Pair(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP),
        Pair(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
        Pair(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE),
        Pair(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE),
        Pair(PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
        Pair(PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
    )

    private val ghostLinePaint = Paint().apply {
        color = Color.parseColor("#E8DCC8")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        alpha = 120
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val ghostDotPaint = Paint().apply {
        color = Color.parseColor("#E8DCC8")
        style = Paint.Style.FILL
        alpha = 140
        isAntiAlias = true
    }

    private val poseLinePaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        alpha = 200
        isAntiAlias = true
    }

    private val poseDotPaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
        alpha = 220
        isAntiAlias = true
    }

    private val alignedLinePaint = Paint().apply {
        color = Color.parseColor("#D4AF37")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        alpha = 255
        isAntiAlias = true
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }

    private val alignedDotPaint = Paint().apply {
        color = Color.parseColor("#D4AF37")
        style = Paint.Style.FILL
        alpha = 255
        isAntiAlias = true
    }

    fun updatePose(pose: Pose?, imgWidth: Int, imgHeight: Int) {
        this.pose = pose
        this.imageWidth = imgWidth
        this.imageHeight = imgHeight
        invalidate()
    }

    fun setAligned(aligned: Boolean) {
        if (isAligned != aligned) {
            isAligned = aligned
            invalidate()
        }
    }

    fun calculateAlignment(): Float {
        val currentPose = pose ?: return 0f
        var matchCount = 0
        var totalCount = 0

        ghostPose.forEach { (landmarkType, ghostPoint) ->
            val landmark = currentPose.getPoseLandmark(landmarkType)
            if (landmark != null && landmark.inFrameLikelihood > 0.5f) {
                val normalizedX = landmark.position.x / imageWidth
                val normalizedY = landmark.position.y / imageHeight
                val dx = abs(normalizedX - ghostPoint.x)
                val dy = abs(normalizedY - ghostPoint.y)
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (distance < 0.08f) matchCount++
                totalCount++
            }
        }
        return if (totalCount > 0) matchCount.toFloat() / totalCount.toFloat() else 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        drawGhostSkeleton(canvas, w, h)
        pose?.let { drawDetectedPose(canvas, it, w, h) }
    }

    private fun drawGhostSkeleton(canvas: Canvas, w: Float, h: Float) {
        connections.forEach { (startType, endType) ->
            val start = ghostPose[startType]
            val end = ghostPose[endType]
            if (start != null && end != null) {
                canvas.drawLine(start.x * w, start.y * h, end.x * w, end.y * h, ghostLinePaint)
            }
        }
        ghostPose.values.forEach { point ->
            canvas.drawCircle(point.x * w, point.y * h, 5f, ghostDotPaint)
        }
    }

    private fun drawDetectedPose(canvas: Canvas, pose: Pose, w: Float, h: Float) {
        val linePaint = if (isAligned) alignedLinePaint else poseLinePaint
        val dotPaint = if (isAligned) alignedDotPaint else poseDotPaint
        val scaleX = w / imageWidth
        val scaleY = h / imageHeight

        connections.forEach { (startType, endType) ->
            val start = pose.getPoseLandmark(startType)
            val end = pose.getPoseLandmark(endType)
            if (start != null && end != null &&
                start.inFrameLikelihood > 0.5f && end.inFrameLikelihood > 0.5f) {
                canvas.drawLine(
                    start.position.x * scaleX, start.position.y * scaleY,
                    end.position.x * scaleX, end.position.y * scaleY, linePaint
                )
            }
        }
        ghostPose.keys.forEach { landmarkType ->
            val landmark = pose.getPoseLandmark(landmarkType)
            if (landmark != null && landmark.inFrameLikelihood > 0.5f) {
                canvas.drawCircle(landmark.position.x * scaleX, landmark.position.y * scaleY, 7f, dotPaint)
            }
        }
    }
}
