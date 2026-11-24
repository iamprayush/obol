package dev.prayush.obol.vision

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class UprightBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun distanceToPoint(px: Float, py: Float): Float {
        val clampedX = px.coerceIn(left, right)
        val clampedY = py.coerceIn(top, bottom)
        val dx = px - clampedX
        val dy = py - clampedY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

data class DetectedFaceInfo(
    val bounds: UprightBox,
    val mouthMidX: Float?,
    val mouthMidY: Float?,
    val noseX: Float?,
    val noseY: Float?,
    val leftEyeOpenProb: Float?,
    val rightEyeOpenProb: Float?,
)

data class VisionFrameAnalysis(
    val frameWidth: Int,
    val frameHeight: Int,
    val meanLuma: Float,
    val faces: List<DetectedFaceInfo>,
    val toothbrushBoxes: List<UprightBox>,
)

interface VisionFrameDetector {
    suspend fun analyzeUprightBitmap(
        uprightBitmap: Bitmap,
        meanLuma: Float,
        detectToothbrush: Boolean,
    ): VisionFrameAnalysis

    fun close()
}

object ImageProxyConverter {

    fun computeMeanYPlaneLuma(imageProxy: ImageProxy): Float {
        val yPlane = imageProxy.planes.firstOrNull() ?: return 0f
        val buffer = yPlane.buffer.duplicate()
        buffer.rewind()
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        if (width <= 0 || height <= 0) return 0f

        var sum = 0L
        var count = 0L
        val step = 4
        var row = 0
        while (row < height) {
            val rowOffset = row * rowStride
            var col = 0
            while (col < width) {
                val idx = rowOffset + col * pixelStride
                if (idx >= 0 && idx < buffer.limit()) {
                    sum += (buffer.get(idx).toInt() and 0xFF)
                    count += 1L
                }
                col += step
            }
            row += step
        }
        return if (count == 0L) 0f else sum.toFloat() / count.toFloat()
    }

    fun toUprightArgbBitmap(imageProxy: ImageProxy): Bitmap {
        val raw = imageProxy.toBitmap()
        val argb = if (raw.config == Bitmap.Config.ARGB_8888) {
            raw
        } else {
            raw.copy(Bitmap.Config.ARGB_8888, false).also {
                if (it !== raw) raw.recycle()
            }
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation % 360 == 0) {
            return argb
        }
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(argb, 0, 0, argb.width, argb.height, matrix, true)
        if (rotated !== argb) {
            argb.recycle()
        }
        return rotated
    }
}

class RealVisionFrameDetector(
    private val context: Context,
) : VisionFrameDetector {

    private val inferenceMutex = Mutex()
    private var initializedFaceDetector: FaceDetector? = null
    private var initializedObjectDetector: ObjectDetector? = null

    private fun obtainFaceDetector(): FaceDetector {
        initializedFaceDetector?.let { return it }
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        val client = try {
            FaceDetection.getClient(options)
        } catch (_: IllegalStateException) {
            ensureMlKitInitializedForDirectBoot(context)
            FaceDetection.getClient(options)
        }
        return client.also { initializedFaceDetector = it }
    }

    private fun obtainObjectDetector(): ObjectDetector {
        initializedObjectDetector?.let { return it }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_NAME)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.28f)
            .setMaxResults(4)
            .setCategoryAllowlist(listOf("toothbrush"))
            .build()
        return ObjectDetector.createFromOptions(context, options).also { initializedObjectDetector = it }
    }

    override suspend fun analyzeUprightBitmap(
        uprightBitmap: Bitmap,
        meanLuma: Float,
        detectToothbrush: Boolean,
    ): VisionFrameAnalysis = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            // Single upright ARGB_8888 coordinate space (rotationDegrees = 0) for both detectors
            val mlImage = InputImage.fromBitmap(uprightBitmap, 0)
            val rawFaces = obtainFaceDetector().process(mlImage).await()

            val mappedFaces = rawFaces.map { face ->
                val rect = face.boundingBox
                val mLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
                val mRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
                val mBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
                val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position

                val mouthX = when {
                    mLeft != null && mRight != null -> (mLeft.x + mRight.x) * 0.5f
                    mBottom != null -> mBottom.x
                    else -> null
                }
                val mouthY = when {
                    mLeft != null && mRight != null -> (mLeft.y + mRight.y) * 0.5f
                    mBottom != null -> mBottom.y
                    else -> null
                }

                DetectedFaceInfo(
                    bounds = UprightBox(
                        left = rect.left.toFloat(),
                        top = rect.top.toFloat(),
                        right = rect.right.toFloat(),
                        bottom = rect.bottom.toFloat(),
                    ),
                    mouthMidX = mouthX,
                    mouthMidY = mouthY,
                    noseX = nose?.x,
                    noseY = nose?.y,
                    leftEyeOpenProb = face.leftEyeOpenProbability,
                    rightEyeOpenProb = face.rightEyeOpenProbability,
                )
            }

            val brushBoxes = if (detectToothbrush) {
                val mpImage = BitmapImageBuilder(uprightBitmap).build()
                val result = obtainObjectDetector().detect(mpImage)
                result.detections().map { det ->
                    val b = det.boundingBox()
                    UprightBox(
                        left = b.left,
                        top = b.top,
                        right = b.right,
                        bottom = b.bottom,
                    )
                }
            } else {
                emptyList()
            }

            VisionFrameAnalysis(
                frameWidth = uprightBitmap.width,
                frameHeight = uprightBitmap.height,
                meanLuma = meanLuma,
                faces = mappedFaces,
                toothbrushBoxes = brushBoxes,
            )
        }
    }

    override fun close() {
        runCatching { initializedFaceDetector?.close() }
        runCatching { initializedObjectDetector?.close() }
        initializedFaceDetector = null
        initializedObjectDetector = null
    }

    companion object {
        const val MODEL_ASSET_NAME = "efficientdet_lite0.tflite"

        internal fun ensureMlKitInitializedForDirectBoot(context: Context) {
            val dpContext = if (context.isDeviceProtectedStorage) {
                context
            } else {
                context.createDeviceProtectedStorageContext()
            }
            val selfAppDpContext = object : ContextWrapper(dpContext) {
                override fun getApplicationContext(): Context = this
            }
            runCatching {
                MlKit.initialize(selfAppDpContext)
            }
        }
    }
}
