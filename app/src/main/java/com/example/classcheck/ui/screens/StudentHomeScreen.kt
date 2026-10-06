package com.example.classcheck.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.classcheck.navigation.Screen
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

enum class CheckInStatus {
    OPEN,
    NOT_STARTED,
    ENDED
}

data class ClassItem(
    val id: String = "",
    val name: String = "",
    val time: String = "",
    val room: String = "",
    val teacher: String = "",
    val program: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentHomeScreen(navController: NavController) {
    var classesList by remember { mutableStateOf<List<ClassItem>>(emptyList()) }
    var sessionStatusMap by remember { mutableStateOf<Map<String, CheckInStatus>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var studentProgram by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentDateStr = remember {
        val sdf = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
        sdf.format(Date())
    }

    LaunchedEffect(Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            try {
                val db = FirebaseFirestore.getInstance()
                val userDoc = db.collection("users").document(user.uid).get().await()
                if (userDoc.exists()) {
                    studentProgram = userDoc.getString("program") ?: ""
                    if (studentProgram.isNotEmpty()) {
                        val classesSnapshot = db.collection("classes")
                            .whereEqualTo("program", studentProgram)
                            .get()
                            .await()

                        val items = classesSnapshot.documents.map { doc ->
                            ClassItem(
                                id = doc.id,
                                name = doc.getString("name") ?: "",
                                time = doc.getString("schedule") ?: "",
                                room = doc.getString("room") ?: "",
                                teacher = doc.getString("teacherName") ?: "",
                                program = doc.getString("program") ?: ""
                            )
                        }
                        classesList = items
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load classes: ${e.localizedMessage}"
            }
        }
        isLoading = false
    }

    // Listen to real-time session changes for student's classes
    DisposableEffect(classesList) {
        if (classesList.isEmpty()) {
            return@DisposableEffect onDispose {}
        }

        val db = FirebaseFirestore.getInstance()
        val classIds = classesList.map { it.id }

        val listener = db.collection("sessions")
            .whereIn("classId", classIds)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val now = Date()
                    val newMap = mutableMapOf<String, CheckInStatus>()
                    val sessionsByClass = snapshot.documents.groupBy { it.getString("classId") ?: "" }

                    for (classId in classIds) {
                        val classSessions = sessionsByClass[classId] ?: emptyList()
                        val activeSession = classSessions.find { doc ->
                            val isActive = doc.getBoolean("isActive") ?: false
                            val endTime = doc.getTimestamp("endTime")?.toDate()
                            isActive && endTime != null && endTime.after(now)
                        }

                        if (activeSession != null) {
                            newMap[classId] = CheckInStatus.OPEN
                        } else if (classSessions.isNotEmpty()) {
                            newMap[classId] = CheckInStatus.ENDED
                        } else {
                            newMap[classId] = CheckInStatus.NOT_STARTED
                        }
                    }
                    sessionStatusMap = newMap
                }
            }

        onDispose {
            listener.remove()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (studentProgram.isNotEmpty()) "Classes ($studentProgram)" else "Today's Classes") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.AttendanceHistory.route) }) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = { navController.navigate(Screen.Profile.route) }) {
                        Icon(Icons.Default.Person, contentDescription = "Profile")
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
                item {
                    Text(
                        text = currentDateStr,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (errorMessage != null) {
                    item {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (classesList.isEmpty()) {
                    item {
                        Text(
                            text = "No classes found for program: $studentProgram",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(classesList) { classItem ->
                        val status = sessionStatusMap[classItem.id] ?: CheckInStatus.NOT_STARTED
                        ClassCard(
                            classItem = classItem,
                            status = status,
                            onCheckInClick = {
                                navController.navigate(Screen.CheckIn.route)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ClassCard(
    classItem: ClassItem,
    status: CheckInStatus,
    onCheckInClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = classItem.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                // Status Badge
                val (statusText, statusColor, containerColor) = when (status) {
                    CheckInStatus.OPEN -> Triple("CHECK-IN OPEN", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                    CheckInStatus.NOT_STARTED -> Triple("CHECK-IN NOT STARTED", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceVariant)
                    CheckInStatus.ENDED -> Triple("CHECK-IN ENDED", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                }

                Surface(
                    color = containerColor,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = classItem.time,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.MeetingRoom,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${classItem.room} • ${classItem.teacher}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Check In Button per class entry
            if (status == CheckInStatus.OPEN) {
                Button(
                    onClick = onCheckInClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check In")
                }
            } else {
                OutlinedButton(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (status == CheckInStatus.NOT_STARTED) "Check In Not Started" else "Check In Ended"
                    )
                }
            }
        }
    }
}
