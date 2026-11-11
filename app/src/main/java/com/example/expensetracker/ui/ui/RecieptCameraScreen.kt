package com.example.expensetracker.ui.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.expensetracker.data.ParsedReceipt
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream


@RequiresApi(Build.VERSION_CODES.P)
@Composable
fun ReceiptCameraScreen(
    expenseViewModel: ExpenseViewModel = hiltViewModel(),
    onExpenseParsed: (ParsedReceipt, String) -> Unit // merchant, amount, category + image path
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    val isParsing by remember { derivedStateOf { expenseViewModel.isParsing } }
    val parsedReceipt = expenseViewModel.parsedReceipt

    // Gallery -> ViewModel
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: android.net.Uri? ->
        uri?.let {
            val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            val path = saveBitmapToInternal(context, bitmap)
            expenseViewModel.onReceiptImage(bitmap, path) // <-- WIRED
        }
    }

    // When AI finishes, navigate
    LaunchedEffect(parsedReceipt) {
        parsedReceipt?.let {
            onExpenseParsed(it, expenseViewModel.lastReceiptPath)
            expenseViewModel.clearParsed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    imageCapture = ImageCapture.Builder().build()
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading overlay
        if (isParsing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("AI reading receipt...", color = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    imageCapture?.let {
                        takePhotoAndScan(it, context, expenseViewModel)
                    }
                },
                enabled = !isParsing
            ) {
                Text("📸 Scan Receipt")
            }
            Button(
                onClick = { galleryLauncher.launch("image/*") },
                enabled = !isParsing
            ) {
                Text("📁 Gallery")
            }
        }
    }
}

private fun saveBitmapToInternal(context: Context, bitmap: Bitmap): String {
    val file = File(context.filesDir, "receipts/gallery_${System.currentTimeMillis()}.jpg")
    file.parentFile?.mkdirs()
    FileOutputStream(file).use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
    }
    return file.absolutePath
}

fun takePhotoAndScan(
    imageCapture: ImageCapture,
    context: Context,
    viewModel: ExpenseViewModel
) {
    val receiptDir = File(context.filesDir, "receipts")
    receiptDir.mkdirs()
    val name = "Receipt_${System.currentTimeMillis()}.jpg"
    val outputFile = File(receiptDir, name)
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

    imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                viewModel.onReceiptImage(bitmap, outputFile.absolutePath) // <-- WIRED
            }
            override fun onError(ex: ImageCaptureException) {
                Log.e("Camera", "Photo capture failed", ex)
            }
        })
}