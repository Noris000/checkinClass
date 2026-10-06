package com.example.classcheck.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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

data class PastSessionStudentRow(
    val studentUid: String,
    val studentName: String,
    val studentId: String,
    val isCheckedIn: Boolean,
    val checkInTime: String?
)

data class SessionRecordUi(
    val sessionId: String,
    val classId: String,
    val className: String,
    val date: String,
    val room: String,
    val present: Int,
    val total: Int,
    val studentsList: List<PastSessionStudentRow>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(navController: NavController) {
    var sessionsList by remember { mutableStateOf<List<SessionRecordUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedSessionForDialog by remember { mutableStateOf<SessionRecordUi?>(null) }

    val dateFormat = remember { SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            try {
                val db = FirebaseFirestore.getInstance()
                val sessionsSnapshot = db.collection("sessions")
                    .whereEqualTo("teacherId", user.uid)
                    .get()
                    .await()

                val list = mutableListOf<SessionRecordUi>()
                for (doc in sessionsSnapshot.documents) {
                    val sessionId = doc.id
                    val classId = doc.getString("classId") ?: continue
                    val startTime = doc.getTimestamp("startTime")?.toDate() ?: Date()

                    // Get class information
                    val classDoc = db.collection("classes").document(classId).get().await()
                    val className = if (classDoc.exists()) classDoc.getString("name") ?: "Class" else "Class"
                    val room = if (classDoc.exists()) classDoc.getString("room") ?: "" else ""

                    // Get assigned students for this class
                    val studentsSnap = db.collection("classStudents")
                        .whereEqualTo("classId", classId)
                        .get()
                        .await()

                    val assignedStudents = studentsSnap.documents.map { sDoc ->
                        Triple(
                            sDoc.getString("studentUid") ?: "",
                            sDoc.getString("studentName") ?: "Student",
                            sDoc.getString("studentId") ?: ""
                        )
                    }

                    // Get check-ins for this session
                    val checkinsSnap = db.collection("checkins")
                        .whereEqualTo("sessionId", sessionId)
                        .get()
                        .await()

                    val checkinsMap = checkinsSnap.documents.associate { cDoc ->
                        val stUid = cDoc.getString("studentUid") ?: ""
                        val ts = cDoc.getTimestamp("timestamp")?.toDate()
                        stUid to ts
                    }

                    // Match students and determine attendance status
                    val studentRows = assignedStudents.map { (uid, name, stId) ->
                        val checkInDate = checkinsMap[uid]
                        PastSessionStudentRow(
                            studentUid = uid,
                            studentName = name,
                            studentId = stId,
                            isCheckedIn = checkInDate != null,
                            checkInTime = checkInDate?.let { timeFormat.format(it) }
                        )
                    }.sortedBy { it.studentName }

                    val present = studentRows.count { it.isCheckedIn }
                    val total = studentRows.size

                    list.add(
                        SessionRecordUi(
                            sessionId = sessionId,
                            classId = classId,
                            className = className,
                            date = dateFormat.format(startTime),
                            room = room,
                            present = present,
                            total = total,
                            studentsList = studentRows
                        )
                    )
                }
                sessionsList = list.sortedByDescending { it.date }
            } catch (e: Exception) {
                errorMessage = "Failed to load session history: ${e.localizedMessage}"
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Past Sessions & Attendance") },
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (errorMessage != null) {
                    item {
                        Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                    }
                } else if (sessionsList.isEmpty()) {
                    item {
                        Text(
                            text = "No past sessions found.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(sessionsList) { session ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSessionForDialog = session
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = session.className,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "${session.date}${if (session.room.isNotEmpty()) " · Room: ${session.room}" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                val progress = if (session.total > 0) session.present.toFloat() / session.total else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${session.present}/${session.total} attended",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Tap to view attendance",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Attendance Modal Dialog Window
        if (selectedSessionForDialog != null) {
            val session = selectedSessionForDialog!!
            AlertDialog(
                onDismissRequest = { selectedSessionForDialog = null },
                title = {
                    Text(
                        text = "Attendance",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = session.className,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = session.date,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (session.room.isNotEmpty()) {
                            Text(
                                text = "Room: ${session.room}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "${session.present}/${session.total} attended",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        if (session.studentsList.isEmpty()) {
                            Text(
                                text = "No students assigned to this class.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            session.studentsList.forEach { student ->
                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (student.isCheckedIn) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Checked in",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = student.studentName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Did not check in",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = student.studentName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (student.isCheckedIn) "Checked in at ${student.checkInTime ?: ""}" else "Did not check in",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 28.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedSessionForDialog = null }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}
