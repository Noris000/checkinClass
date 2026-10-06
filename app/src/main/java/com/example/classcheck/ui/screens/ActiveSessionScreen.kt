package com.example.classcheck.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.classcheck.utils.generateQRCodeBitmap
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

data class StudentAttendanceRow(
    val studentUid: String,
    val studentName: String,
    val studentId: String,
    val isCheckedIn: Boolean,
    val checkInTime: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionScreen(navController: NavController, sessionId: String, classId: String) {
    var className by remember { mutableStateOf("Loading Class...") }
    var classRoom by remember { mutableStateOf("") }
    var classSchedule by remember { mutableStateOf("") }
    var sessionCode by remember { mutableStateOf("------") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var studentsList by remember { mutableStateOf<List<StudentAttendanceRow>>(emptyList()) }
    var checkedInCount by remember { mutableStateOf(0) }
    var totalAssigned by remember { mutableStateOf(0) }
    var isEnding by remember { mutableStateOf(false) }
    var isExpired by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableStateOf(300L) }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Session timer countdown & expiration handler
    LaunchedEffect(sessionId) {
        val db = FirebaseFirestore.getInstance()
        val sessionDocRef = db.collection("sessions").document(sessionId)
        val snapshot = sessionDocRef.get().await()
        if (snapshot.exists()) {
            val endTime = snapshot.getTimestamp("endTime")?.toDate()
            if (endTime != null) {
                while (true) {
                    val now = Date()
                    val diff = (endTime.time - now.time) / 1000
                    if (diff <= 0) {
                        remainingSeconds = 0
                        isExpired = true
                        // Automatically update Firestore session to inactive
                        try {
                            sessionDocRef.update("isActive", false).await()
                        } catch (_: Exception) {}
                        break
                    } else {
                        remainingSeconds = diff
                    }
                    delay(1000)
                }
            }
        }
    }

    // Realtime listeners for session, classStudents, and checkins
    DisposableEffect(sessionId, classId) {
        val db = FirebaseFirestore.getInstance()

        // 1. Fetch class details
        db.collection("classes").document(classId).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                className = doc.getString("name") ?: ""
                classRoom = doc.getString("room") ?: ""
                classSchedule = doc.getString("schedule") ?: ""
            }
        }

        // 2. Listen to session doc
        val sessionListener = db.collection("sessions").document(sessionId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val code = snapshot.getString("code") ?: "------"
                    sessionCode = code
                    val qrContent = "classcheck://$sessionId/$code"
                    qrBitmap = generateQRCodeBitmap(qrContent)
                    val isActive = snapshot.getBoolean("isActive") ?: true
                    if (!isActive) {
                        isExpired = true
                    }
                }
            }

        // 3. Listen to classStudents and checkins to compute live attendance
        var assignedMap = emptyMap<String, Pair<String, String>>()
        var checkinsMap = emptyMap<String, Date>()

        val updateList = {
            val rows = assignedMap.map { (uid, info) ->
                val checkedInTime = checkinsMap[uid]
                StudentAttendanceRow(
                    studentUid = uid,
                    studentName = info.first,
                    studentId = info.second,
                    isCheckedIn = checkedInTime != null,
                    checkInTime = checkedInTime?.let { timeFormat.format(it) }
                )
            }.sortedBy { it.studentName }

            studentsList = rows
            totalAssigned = rows.size
            checkedInCount = rows.count { it.isCheckedIn }
        }

        val studentsReg = db.collection("classStudents")
            .whereEqualTo("classId", classId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    assignedMap = snapshot.documents.associate { doc ->
                        val uid = doc.getString("studentUid") ?: ""
                        val name = doc.getString("studentName") ?: "Student"
                        val stId = doc.getString("studentId") ?: ""
                        uid to (name to stId)
                    }
                    updateList()
                }
            }

        val checkinsReg = db.collection("checkins")
            .whereEqualTo("sessionId", sessionId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    checkinsMap = snapshot.documents.associate { doc ->
                        val uid = doc.getString("studentUid") ?: ""
                        val ts = doc.getTimestamp("timestamp")?.toDate() ?: Date()
                        uid to ts
                    }
                    updateList()
                }
            }

        onDispose {
            sessionListener.remove()
            studentsReg.remove()
            checkinsReg.remove()
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isExpired) "Session Ended" else "Active Session") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isExpired) {
                        TextButton(
                            onClick = {
                                isEnding = true
                                FirebaseFirestore.getInstance().collection("sessions")
                                    .document(sessionId)
                                    .update("isActive", false)
                                    .addOnCompleteListener {
                                        isExpired = true
                                        isEnding = false
                                    }
                            },
                            enabled = !isEnding
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("End")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = className,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Room $classRoom • $classSchedule",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // QR Code display or Expired state
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isExpired) {
                    Text(
                        text = "SESSION\nEXPIRED",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Session QR Code",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6-digit code or timer status
            if (!isExpired) {
                Text(
                    text = "Code",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = sessionCode.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Valid for $timeFormatted",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (remainingSeconds < 60) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "This session has ended.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Live list header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Attendance",
                    style = MaterialTheme.typography.titleMedium
                )
                Badge {
                    Text("$checkedInCount / $totalAssigned")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(studentsList) { student ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = student.studentName,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = student.studentId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (student.isCheckedIn) {
                                Text(
                                    text = "✓ Checked in (${student.checkInTime})",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = "Waiting",
                                    color = MaterialTheme.colorScheme.outline,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
