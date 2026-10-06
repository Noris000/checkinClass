package com.example.classcheck.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.classcheck.navigation.Screen
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

data class TeacherClassModel(
    val id: String = "",
    val name: String = "",
    val time: String = "",
    val room: String = "",
    val program: String = "",
    val teacherId: String = "",
    val buildingLat: Double = 0.0,
    val buildingLng: Double = 0.0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherHomeScreen(navController: NavController) {
    var classesList by remember { mutableStateOf<List<TeacherClassModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var startingClassId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            try {
                val db = FirebaseFirestore.getInstance()
                val snapshot = db.collection("classes")
                    .whereEqualTo("teacherId", user.uid)
                    .get()
                    .await()

                classesList = snapshot.documents.map { doc ->
                    TeacherClassModel(
                        id = doc.id,
                        name = doc.getString("name") ?: "",
                        time = doc.getString("schedule") ?: "",
                        room = doc.getString("room") ?: "",
                        program = doc.getString("program") ?: "",
                        teacherId = doc.getString("teacherId") ?: "",
                        buildingLat = parseCoord(doc.get("buildingLat")),
                        buildingLng = parseCoord(doc.get("buildingLng"))
                    )
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load classes: ${e.localizedMessage}"
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Classes (Teacher)") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.SessionHistory.route) }) {
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
                        text = "Start a check-in session",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (errorMessage != null) {
                    item {
                        Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                    }
                } else if (classesList.isEmpty()) {
                    item {
                        Text(text = "No classes assigned to you.")
                    }
                } else {
                    items(classesList) { classItem ->
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = classItem.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${classItem.time} • ${classItem.room} (${classItem.program})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            startingClassId = classItem.id
                                            try {
                                                val user = FirebaseAuth.getInstance().currentUser ?: return@launch
                                                val db = FirebaseFirestore.getInstance()
                                                val code = (100000..999999).random().toString()
                                                val now = Timestamp.now()
                                                val endTime = Timestamp(Date(System.currentTimeMillis() + 5 * 60 * 1000))

                                                val sessionMap = hashMapOf(
                                                    "classId" to classItem.id,
                                                    "teacherId" to user.uid,
                                                    "code" to code,
                                                    "isActive" to true,
                                                    "startTime" to now,
                                                    "endTime" to endTime,
                                                    "locationLat" to classItem.buildingLat,
                                                    "locationLng" to classItem.buildingLng
                                                )

                                                val sessionRef = db.collection("sessions").add(sessionMap).await()
                                                navController.navigate(Screen.ActiveSession.createRoute(sessionRef.id, classItem.id))
                                            } catch (e: Exception) {
                                                errorMessage = "Failed to start session: ${e.localizedMessage}"
                                            } finally {
                                                startingClassId = null
                                            }
                                        }
                                    },
                                    enabled = startingClassId != classItem.id,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (startingClassId == classItem.id) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Start Check-in Session")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun parseCoord(value: Any?): Double {
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}
