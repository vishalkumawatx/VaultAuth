package com.example.authanticator.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.authanticator.ui.Page
import com.example.authanticator.ui.PageHeader
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScannerScreen(detected: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) error = "Camera access was not granted. You can enter the setup key manually instead."
    }
    Page {
        PageHeader("Scan QR code", back)
        if (granted) {
            CameraPreview(detected, { error = it })
            Text("Scan a TOTP or HOTP setup QR code from your service. Images and setup keys stay on this device.")
        } else {
            Text("Camera access is needed only to read a setup QR code. No photos are saved or uploaded.")
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
        error?.let { Text(it) }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(detected: (String) -> Unit, failed: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val onDetected by rememberUpdatedState(detected)
    val onFailed by rememberUpdatedState(failed)
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 420.dp).aspectRatio(1f))
    DisposableEffect(lifecycle) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val closed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            val image = proxy.image
            if (image == null || closed.get() || delivered.get()) proxy.close()
            else {
                try {
                    scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener(main) { barcodes ->
                            val raw = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                            if (!closed.get() && raw != null && delivered.compareAndSet(false, true)) onDetected(raw)
                        }
                        .addOnFailureListener(main) { if (!closed.get()) onFailed("QR scanning failed. Try again or use manual entry.") }
                        .addOnCompleteListener { proxy.close() }
                } catch (_: Exception) { proxy.close() }
            }
        }
        future.addListener({
            if (!closed.get()) {
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    cameraProvider.bindToLifecycle(lifecycle, selector, preview, analysis)
                } catch (_: Exception) { onFailed("Camera is unavailable. Use manual entry or check camera permission in Android settings.") }
            }
        }, main)
        onDispose {
            closed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            scanner.close()
            executor.shutdown()
        }
    }
}