package com.example.classcheck.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

data class SessionRecordUi(
    val className: String,
    val date: String,
    val present: Int,
    val total: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(navController: NavController) {
    var sessionsList by remember { mutableStateOf<List<SessionRecordUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("d MMM yyyy • HH:mm", Locale.getDefault()) }

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

                    // Get class name
                    val classDoc = db.collection("classes").document(classId).get().await()
                    val className = if (classDoc.exists()) classDoc.getString("name") ?: "Class" else "Class"

                    // Get total assigned students
                    val studentsSnap = db.collection("classStudents")
                        .whereEqualTo("classId", classId)
                        .get()
                        .await()
                    val total = studentsSnap.size()

                    // Get checked in count
                    val checkinsSnap = db.collection("checkins")
                        .whereEqualTo("sessionId", sessionId)
                        .get()
                        .await()
                    val present = checkinsSnap.size()

                    list.add(
                        SessionRecordUi(
                            className = className,
                            date = dateFormat.format(startTime),
                            present = present,
                            total = total
                        )
                    )
                }
                sessionsList = list
            } catch (e: Exception) {
                errorMessage = "Failed to load session history: ${e.localizedMessage}"
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Teacher Session History") },
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
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = session.className,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = session.date,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val progress = if (session.total > 0) session.present.toFloat() / session.total else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${session.present} / ${session.total} present",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
