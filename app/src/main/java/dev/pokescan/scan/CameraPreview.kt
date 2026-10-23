package dev.pokescan.scan

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Size
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val MIN_FRAME_INTERVAL_MS = 250L
/** How long a tap-to-focus point is held before the camera returns to continuous autofocus. */
private const val FOCUS_HOLD_SECONDS = 5L
private const val FOCUS_RING_VISIBLE_MS = 800L

/**
 * Camera preview that runs on-device OCR on the live feed and reports recognized text.
 * Tapping the preview focuses there, which helps when the camera locks onto a sleeve or
 * toploader instead of the card behind it.
 */
@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(modifier: Modifier = Modifier, onText: (Text) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            recognizer.close()
            executor.shutdown()
        }
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(FOCUS_RING_VISIBLE_MS)
            focusPoint = null
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                var camera: Camera? = null

                previewView.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                        )
                            .setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS)
                            .build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                        focusPoint = Offset(event.x, event.y)
                        view.performClick()
                    }
                    true
                }

                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    // 1080p keeps the small collector number legible, even through a sleeve.
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1920, 1080),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    )
                                )
                                .build()
                        )
                        .build()

                    var lastFrameAt = 0L
                    analysis.setAnalyzer(executor) { proxy ->
                        val now = SystemClock.elapsedRealtime()
                        val media = proxy.image
                        if (media == null || now - lastFrameAt < MIN_FRAME_INTERVAL_MS) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        lastFrameAt = now
                        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        recognizer.process(input)
                            .addOnSuccessListener { currentOnText(it) }
                            .addOnCompleteListener { proxy.close() }
                    }

                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        focusPoint?.let { point ->
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White,
                    radius = 36.dp.toPx(),
                    center = point,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}
