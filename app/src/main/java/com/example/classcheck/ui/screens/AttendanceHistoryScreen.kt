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
    val className: String,
    val date: String,
    val time: String,
    val room: String,
    val present: Boolean
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
                val checkinsSnapshot = db.collection("checkins")
                    .whereEqualTo("studentUid", user.uid)
                    .get()
                    .await()

                val list = mutableListOf<AttendanceRecordUi>()
                for (doc in checkinsSnapshot.documents) {
                    val sessionId = doc.getString("sessionId") ?: continue
                    val timestamp = doc.getTimestamp("timestamp")?.toDate() ?: Date()

                    val sessionDoc = db.collection("sessions").document(sessionId).get().await()
                    if (!sessionDoc.exists()) continue

                    val classId = sessionDoc.getString("classId") ?: continue
                    val classDoc = db.collection("classes").document(classId).get().await()
                    if (!classDoc.exists()) continue

                    val className = classDoc.getString("name") ?: "Class"
                    val room = classDoc.getString("room") ?: ""

                    list.add(
                        AttendanceRecordUi(
                            className = className,
                            date = dateFormat.format(timestamp),
                            time = timeFormat.format(timestamp),
                            room = room,
                            present = true
                        )
                    )
                }
                records = list.sortedByDescending { it.date }
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
                            text = "No attendance records found.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(records) { record ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (record.present) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = record.className,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${record.date} • Room ${record.room} • ${record.time}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "Attended",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
