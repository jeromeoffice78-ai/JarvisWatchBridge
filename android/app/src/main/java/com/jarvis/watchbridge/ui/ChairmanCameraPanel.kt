package com.jarvis.watchbridge.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

@Composable
fun ChairmanCameraPanel(permissionGranted: Boolean, analyzing: Boolean, onRequestPermission: () -> Unit, onFrameCaptured: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(72).build() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(permissionGranted, lifecycleOwner) {
        if (permissionGranted) providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() } }
    }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101826))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CHAIRMAN CAMERA • JARVIS VISION", color = Color(0xFF59C9FF))
            if (!permissionGranted) {
                Text("Camera access is off. It activates only while this panel is open.", color = Color(0xFFA8BDD0))
                Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("ALLOW FRONT CAMERA") }
            } else {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(300.dp))
                Button(enabled = !analyzing, onClick = {
                    val file = File.createTempFile("jarvis-vision-", ".jpg", context.cacheDir)
                    imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { try { onFrameCaptured(file.readBytes()) } finally { file.delete() } }
                        override fun onError(exception: ImageCaptureException) { file.delete() }
                    })
                }, modifier = Modifier.fillMaxWidth()) { Text(if (analyzing) "JARVIS IS LOOKING…" else "LET JARVIS SEE ME") }
            }
        }
    }
}
