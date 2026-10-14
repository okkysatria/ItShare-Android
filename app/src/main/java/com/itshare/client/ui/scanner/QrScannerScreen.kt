package com.itshare.client.ui.scanner

import android.Manifest
import android.util.Log
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.itshare.client.ui.uiText
import java.util.concurrent.Executors

@Composable
fun QrScannerScreen(
    language: String = "en",
    onQrCodeDetected: (String) -> Unit,
    onManualInputClicked: () -> Unit,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClicked)
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreviewWithScanner(
                onQrScanned = onQrCodeDetected,
                modifier = Modifier.fillMaxSize()
            )

            ScannerReticleOverlay(
                modifier = Modifier.fillMaxSize()
            )
        } else {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = uiText(language, "Camera permission required", "Izin kamera diperlukan"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiText(language, "Allow camera access to scan a QR code.", "Izinkan akses kamera untuk memindai kode QR."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(uiText(language, "Allow camera", "Izinkan kamera"))
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClicked,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText(language, "Back", "Kembali"))
            }

            Text(
                text = uiText(language, "Scan QR", "Pindai QR"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )

            IconButton(
                onClick = onManualInputClicked,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Edit, contentDescription = uiText(language, "Enter manually", "Input manual"))
            }
        }
    }
}

@Composable
fun CameraPreviewWithScanner(
    onQrScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isScanned by remember { mutableStateOf(false) }
    var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var activeBarcodeScanner by remember { mutableStateOf<com.google.mlkit.vision.barcode.BarcodeScanner?>(null) }
    var activeExecutor by remember { mutableStateOf<java.util.concurrent.ExecutorService?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            boundCameraProvider?.unbindAll()
            activeBarcodeScanner?.close()
            activeExecutor?.shutdownNow()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraExecutor = Executors.newSingleThreadExecutor()
            activeExecutor = cameraExecutor
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                boundCameraProvider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val barcodeOptions = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val barcodeScanner = BarcodeScanning.getClient(barcodeOptions)
                activeBarcodeScanner = barcodeScanner

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(barcodeScanner, imageProxy) { barcodeValue ->
                        if (!isScanned) {
                            isScanned = true
                            val kind = when {
                                barcodeValue.trimStart().startsWith("{") -> "SMB_JSON_CANDIDATE"
                                barcodeValue.trimStart().startsWith("WIFI:", ignoreCase = true) -> "WIFI"
                                else -> "UNKNOWN"
                            }
                            Log.i("ItShareScan", "QR detected: kind=$kind, length=${barcodeValue.length}")
                            onQrScanned(barcodeValue)
                        }
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (error: Exception) {
                    Log.e("ItShareScan", "Failed to bind CameraX preview/analyzer", error)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onSuccess: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { raw ->
                        onSuccess(raw)
                        return@addOnSuccessListener
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
            .addOnFailureListener { error ->
                Log.e("ItShareScan", "ML Kit failed to analyze camera frame", error)
            }
    } else {
        imageProxy.close()
    }
}

@Composable
fun ScannerReticleOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val boxSize = size.width * 0.7f
        val left = (size.width - boxSize) / 2
        val top = (size.height - boxSize) / 2

        drawRect(
            color = Color.Black.copy(alpha = 0.55f),
            size = size
        )

        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(boxSize, boxSize),
            cornerRadius = CornerRadius(24.dp.toPx()),
            blendMode = BlendMode.Clear
        )

        val cornerLen = 28.dp.toPx()
        val strokeW = 4.dp.toPx()
        val cornerColor = Color(0xFF8FE0B3)

        drawLine(cornerColor, Offset(left + 12.dp.toPx(), top), Offset(left + 12.dp.toPx() + cornerLen, top), strokeW)
        drawLine(cornerColor, Offset(left, top + 12.dp.toPx()), Offset(left, top + 12.dp.toPx() + cornerLen), strokeW)

        drawLine(cornerColor, Offset(left + boxSize - 12.dp.toPx() - cornerLen, top), Offset(left + boxSize - 12.dp.toPx(), top), strokeW)
        drawLine(cornerColor, Offset(left + boxSize, top + 12.dp.toPx()), Offset(left + boxSize, top + 12.dp.toPx() + cornerLen), strokeW)

        drawLine(cornerColor, Offset(left + 12.dp.toPx(), top + boxSize), Offset(left + 12.dp.toPx() + cornerLen, top + boxSize), strokeW)
        drawLine(cornerColor, Offset(left, top + boxSize - 12.dp.toPx() - cornerLen), Offset(left, top + boxSize - 12.dp.toPx()), strokeW)

        drawLine(cornerColor, Offset(left + boxSize - 12.dp.toPx() - cornerLen, top + boxSize), Offset(left + boxSize - 12.dp.toPx(), top + boxSize), strokeW)
        drawLine(cornerColor, Offset(left + boxSize, top + boxSize - 12.dp.toPx() - cornerLen), Offset(left + boxSize, top + boxSize - 12.dp.toPx()), strokeW)
    }
}
