package com.example.classcheck.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.classcheck.navigation.Screen
import com.example.classcheck.utils.calculateDistanceMeters
import com.google.android.gms.location.LocationServices
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.Executors

suspend fun getCameraProvider(context: Context): ProcessCameraProvider =
    withContext(Dispatchers.IO) {
        ProcessCameraProvider.getInstance(context).get()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(navController: NavController) {
    var code by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasScanned by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (!cameraGranted) {
            errorMessage = "Camera permission is required to scan QR codes."
        }
        if (!locationGranted) {
            errorMessage = "Location permission is required to verify your location."
        }
    }

    LaunchedEffect(Unit) {
        val camPerm = Manifest.permission.CAMERA
        val locPerm = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(context, camPerm) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, locPerm) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsLauncher.launch(arrayOf(camPerm, locPerm))
        }
    }

    fun performCheckIn(targetSessionId: String?, targetCode: String?) {
        if (hasScanned && targetSessionId != null) return
        if (targetSessionId != null) {
            hasScanned = true
        }

        scope.launch {
            isLoading = true
            errorMessage = null

            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    errorMessage = "User not authenticated."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }

                // 1. Location permission check
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    errorMessage = "Location permission not granted."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }

                // 2. Get current device location
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                val location = fusedLocationClient.lastLocation.await()
                if (location == null) {
                    errorMessage = "Unable to get current location. Please ensure GPS is enabled."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }
                val studentLat = location.latitude
                val studentLng = location.longitude

                val db = FirebaseFirestore.getInstance()

                // 3. Find active session
                val sessionDoc = if (!targetSessionId.isNullOrEmpty()) {
                    db.collection("sessions").document(targetSessionId).get().await()
                } else if (!targetCode.isNullOrEmpty()) {
                    val snapshot = db.collection("sessions")
                        .whereEqualTo("code", targetCode)
                        .whereEqualTo("isActive", true)
                        .get()
                        .await()
                    snapshot.documents.firstOrNull()
                } else {
                    null
                }

                if (sessionDoc == null || !sessionDoc.exists()) {
                    errorMessage = "Invalid or expired session code/QR."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }

                val sessionId = sessionDoc.id
                val classId = sessionDoc.getString("classId") ?: ""
                val isActive = sessionDoc.getBoolean("isActive") ?: false
                val endTime = sessionDoc.getTimestamp("endTime")?.toDate()
                val sessionLat = sessionDoc.getDouble("locationLat") ?: 0.0
                val sessionLng = sessionDoc.getDouble("locationLng") ?: 0.0

                if (!isActive) {
                    errorMessage = "Session is inactive or has been ended by the teacher."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }

                if (endTime != null && Date().after(endTime)) {
                    errorMessage = "Session has expired."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }

                // 4. Verify student is assigned to class
                val assignmentSnapshot = db.collection("classStudents")
                    .whereEqualTo("classId", classId)
                    .whereEqualTo("studentUid", user.uid)
                    .get()
                    .await()

                if (assignmentSnapshot.isEmpty) {
                    errorMessage = "You are not assigned to this class."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }

                // 5. Verify distance (MAX_CHECKIN_DISTANCE_METERS = 100 meters)
                val distanceMeters = calculateDistanceMeters(studentLat, studentLng, sessionLat, sessionLng)
                val maxDistance = 100.0
                if (distanceMeters > maxDistance) {
                    errorMessage = "You are too far from the classroom to check in (${distanceMeters.toInt()}m away, max ${maxDistance.toInt()}m)."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }

                // 6. Prevent duplicate check-in
                val checkinDocId = "${sessionId}_${user.uid}"
                val checkinRef = db.collection("checkins").document(checkinDocId)
                val existingCheckin = checkinRef.get().await()

                if (existingCheckin.exists()) {
                    errorMessage = "Already checked in."
                    isLoading = false
                    hasScanned = false
                    return@launch
                }

                // 7. Create checkin document
                val checkinMap = hashMapOf(
                    "sessionId" to sessionId,
                    "studentUid" to user.uid,
                    "timestamp" to Timestamp.now(),
                    "lat" to studentLat,
                    "lng" to studentLng
                )
                checkinRef.set(checkinMap).await()

                navController.navigate(Screen.CheckInSuccess.route) {
                    popUpTo(Screen.StudentHome.route)
                }
            } catch (e: Exception) {
                errorMessage = "Check-in failed: ${e.localizedMessage}"
                isLoading = false
                hasScanned = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check In") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Real CameraX QR Scanner Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            scope.launch {
                                try {
                                    val cameraProvider = getCameraProvider(ctx)
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    val scanner = BarcodeScanning.getClient()
                                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null && !hasScanned) {
                                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                            scanner.process(image)
                                                .addOnSuccessListener { barcodes ->
                                                    for (barcode in barcodes) {
                                                        val rawValue = barcode.rawValue
                                                        if (rawValue != null && rawValue.startsWith("classcheck://")) {
                                                            val parts = rawValue.removePrefix("classcheck://").split("/")
                                                            if (parts.isNotEmpty()) {
                                                                val scannedSessionId = parts[0]
                                                                performCheckIn(scannedSessionId, null)
                                                                break
                                                            }
                                                        }
                                                    }
                                                }
                                                .addOnFailureListener {}
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }

                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (_: Exception) {}
                            }
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Camera Permission Required",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(
                text = "— or enter 6-digit session code —",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = code,
                onValueChange = {
                    if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                        code = it
                    }
                },
                label = { Text("6-digit code") },
                placeholder = { Text("e.g. 482917") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { performCheckIn(null, code) },
                enabled = code.length == 6 && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check In with Code")
                }
            }
        }
    }
}
