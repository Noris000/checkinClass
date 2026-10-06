package com.example.classcheck.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

data class AttendanceRecordUi(
    val sessionId: String,
    val className: String,
    val date: String,
    val sessionTime: String,
    val room: String,
    val isPresent: Boolean,
    val checkInTime: String?,
    val startTime: Date
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceHistoryScreen(navController: NavController) {
    var records by remember { mutableStateOf<List<AttendanceRecordUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            try {
                val db = FirebaseFirestore.getInstance()

                // 1. Get student program
                val userDoc = db.collection("users").document(user.uid).get().await()
                val program = userDoc.getString("program") ?: ""

                if (program.isNotEmpty()) {
                    // 2. Query classes matching student's program
                    val classesSnap = db.collection("classes")
                        .whereEqualTo("program", program)
                        .get()
                        .await()

                    val classesMap = classesSnap.documents.associateBy { it.id }
                    val classIds = classesMap.keys.toList()

                    if (classIds.isNotEmpty()) {
                        // 3. Query sessions for these classes
                        val sessionsSnap = db.collection("sessions")
                            .whereIn("classId", classIds)
                            .get()
                            .await()

                        // 4. Query student's check-ins
                        val checkinsSnap = db.collection("checkins")
                            .whereEqualTo("studentUid", user.uid)
                            .get()
                            .await()

                        val checkinsBySession = checkinsSnap.documents.associateBy {
                            it.getString("sessionId") ?: ""
                        }

                        val now = Date()
                        val list = mutableListOf<AttendanceRecordUi>()

                        for (sDoc in sessionsSnap.documents) {
                            val isActive = sDoc.getBoolean("isActive") ?: false
                            val endTime = sDoc.getTimestamp("endTime")?.toDate()

                            // A lecture becomes finished when isActive == false OR currentTime >= endTime
                            val isFinished = !isActive || (endTime != null && !endTime.after(now))
                            if (!isFinished) continue

                            val sessionId = sDoc.id
                            val classId = sDoc.getString("classId") ?: continue
                            val classDoc = classesMap[classId] ?: continue

                            val className = classDoc.getString("name") ?: "Class"
                            val room = classDoc.getString("room") ?: ""
                            val startTime = sDoc.getTimestamp("startTime")?.toDate() ?: Date()

                            val checkinDoc = checkinsBySession[sessionId]
                            val isPresent = checkinDoc != null
                            val checkInTimeStr = checkinDoc?.getTimestamp("timestamp")?.toDate()?.let {
                                timeFormat.format(it)
                            }

                            list.add(
                                AttendanceRecordUi(
                                    sessionId = sessionId,
                                    className = className,
                                    date = dateFormat.format(startTime),
                                    sessionTime = timeFormat.format(startTime),
                                    room = room,
                                    isPresent = isPresent,
                                    checkInTime = checkInTimeStr,
                                    startTime = startTime
                                )
                            )
                        }

                        records = list.sortedByDescending { it.startTime }
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load attendance history: ${e.localizedMessage}"
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Attendance History") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (errorMessage != null) {
                    item {
                        Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                    }
                } else if (records.isEmpty()) {
                    item {
                        Text(
                            text = "No past lecture history available.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(records) { record ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (record.isPresent) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = if (record.isPresent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = record.className,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${record.date} • ${record.sessionTime}${if (record.room.isNotEmpty()) " • Room: ${record.room}" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (record.isPresent && record.checkInTime != null) {
                                        Text(
                                            text = "Checked in at ${record.checkInTime}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else if (!record.isPresent) {
                                        Text(
                                            text = "Did not check in",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Surface(
                                    color = if (record.isPresent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.extraSmall
                                ) {
                                    Text(
                                        text = if (record.isPresent) "PRESENT" else "ABSENT",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (record.isPresent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
