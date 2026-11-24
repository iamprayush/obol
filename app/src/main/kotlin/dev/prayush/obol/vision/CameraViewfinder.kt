package dev.prayush.obol.vision

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.prayush.obol.domain.time.ObolClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch

@Composable
fun CameraViewfinder(
    detector: VisionFrameDetector,
    clock: ObolClock,
    detectToothbrush: Boolean,
    onFrameAnalyzed: (VisionFrameAnalysis) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val currentDetectToothbrush = rememberUpdatedState(detectToothbrush)
    val currentOnFrame = rememberUpdatedState(onFrameAnalyzed)
    val currentOnError = rememberUpdatedState(onCameraError)
    val currentClock = rememberUpdatedState(clock)

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, detector) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val isProcessing = AtomicBoolean(false)
        val lastFrameElapsedMs = AtomicLong(Long.MIN_VALUE / 2)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        val listener = Runnable {
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val nowMs = currentClock.value.elapsedMs()
                    val prevMs = lastFrameElapsedMs.get()
                    if ((nowMs >= prevMs && nowMs - prevMs < MIN_FRAME_INTERVAL_MS) ||
                        !isProcessing.compareAndSet(false, true)
                    ) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastFrameElapsedMs.set(nowMs)

                    val (meanLuma, uprightBitmap) = try {
                        val luma = ImageProxyConverter.computeMeanYPlaneLuma(imageProxy)
                        val bmp = ImageProxyConverter.toUprightArgbBitmap(imageProxy)
                        luma to bmp
                    } catch (_: Throwable) {
                        isProcessing.set(false)
                        previewView.post { currentOnError.value.invoke() }
                        return@setAnalyzer
                    } finally {
                        imageProxy.close()
                    }

                    coroutineScope.launch {
                        try {
                            val analysis = detector.analyzeUprightBitmap(
                                uprightBitmap = uprightBitmap,
                                meanLuma = meanLuma,
                                detectToothbrush = currentDetectToothbrush.value,
                            )
                            currentOnFrame.value.invoke(analysis)
                        } catch (_: Throwable) {
                            currentOnError.value.invoke()
                        } finally {
                            uprightBitmap.recycle()
                            isProcessing.set(false)
                        }
                    }
                }

                cameraProvider.unbindAll()
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis,
                )
            }.onFailure {
                currentOnError.value.invoke()
            }
        }

        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching {
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            }
            analysisExecutor.shutdownNow()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

private const val MIN_FRAME_INTERVAL_MS = 120L
