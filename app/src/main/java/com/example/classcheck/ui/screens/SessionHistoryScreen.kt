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
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
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
    val isEnded: Boolean,
    val studentsList: List<PastSessionStudentRow>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(navController: NavController) {
    var sessionsList by remember { mutableStateOf<List<SessionRecordUi>>(emptyList()) }
    var lastDocument by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var isPageLoading by remember { mutableStateOf(false) }
    var hasMoreSessions by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedSessionForDialog by remember { mutableStateOf<SessionRecordUi?>(null) }
    var isUpdatingAttendance by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Identify the most recently ended session ID across all loaded sessions
    val latestEndedSessionId = remember(sessionsList) {
        sessionsList.firstOrNull { it.isEnded }?.sessionId
    }

    fun loadNextPage() {
        if (isPageLoading || !hasMoreSessions) return
        isPageLoading = true
        errorMessage = null

        scope.launch {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    isPageLoading = false
                    isInitialLoading = false
                    return@launch
                }

                val db = FirebaseFirestore.getInstance()
                var query = db.collection("sessions")
                    .whereEqualTo("teacherId", user.uid)
                    .orderBy("startTime", Query.Direction.DESCENDING)
                    .limit(10)

                if (lastDocument != null) {
                    query = query.startAfter(lastDocument!!)
                }

                val snapshot = query.get().await()
                val docs = snapshot.documents

                if (docs.isEmpty()) {
                    hasMoreSessions = false
                    isPageLoading = false
                    isInitialLoading = false
                    return@launch
                }

                lastDocument = docs.last()
                if (docs.size < 10) {
                    hasMoreSessions = false
                }

                val pageItems = mutableListOf<SessionRecordUi>()
                val now = Date()

                for (sDoc in docs) {
                    val sessionId = sDoc.id
                    val classId = sDoc.getString("classId") ?: continue
                    val startTime = sDoc.getTimestamp("startTime")?.toDate() ?: Date()
                    val endTime = sDoc.getTimestamp("endTime")?.toDate()
                    val isActive = sDoc.getBoolean("isActive") ?: false

                    val isEnded = !isActive || (endTime != null && !endTime.after(now))

                    // Get class information
                    val classDoc = db.collection("classes").document(classId).get().await()
                    val className = if (classDoc.exists()) classDoc.getString("name") ?: "Class" else "Class"
                    val room = if (classDoc.exists()) classDoc.getString("room") ?: "" else ""

                    // Get assigned students for this class
                    val studentsSnap = db.collection("classStudents")
                        .whereEqualTo("classId", classId)
                        .get()
                        .await()

                    val assignedStudents = studentsSnap.documents.map { stDoc ->
                        Triple(
                            stDoc.getString("studentUid") ?: "",
                            stDoc.getString("studentName") ?: "Student",
                            stDoc.getString("studentId") ?: ""
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

                    pageItems.add(
                        SessionRecordUi(
                            sessionId = sessionId,
                            classId = classId,
                            className = className,
                            date = dateFormat.format(startTime),
                            room = room,
                            present = present,
                            total = total,
                            isEnded = isEnded,
                            studentsList = studentRows
                        )
                    )
                }

                val existingIds = sessionsList.map { it.sessionId }.toSet()
                val uniqueNewItems = pageItems.filter { it.sessionId !in existingIds }
                sessionsList = sessionsList + uniqueNewItems

            } catch (e: Exception) {
                errorMessage = "Failed to load sessions: ${e.localizedMessage}"
            } finally {
                isPageLoading = false
                isInitialLoading = false
            }
        }
    }

    fun toggleStudentAttendance(
        session: SessionRecordUi,
        student: PastSessionStudentRow
    ) {
        if (isUpdatingAttendance) return
        isUpdatingAttendance = true

        scope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                val checkinDocRef = db.collection("checkins").document("${session.sessionId}_${student.studentUid}")

                val newIsCheckedIn = !student.isCheckedIn
                val now = Date()
                val newCheckInTimeStr = if (newIsCheckedIn) timeFormat.format(now) else null

                if (newIsCheckedIn) {
                    val checkinMap = hashMapOf(
                        "sessionId" to session.sessionId,
                        "studentUid" to student.studentUid,
                        "timestamp" to Timestamp.now(),
                        "lat" to 0.0,
                        "lng" to 0.0
                    )
                    checkinDocRef.set(checkinMap).await()
                } else {
                    checkinDocRef.delete().await()
                }

                val updatedStudentsList = session.studentsList.map {
                    if (it.studentUid == student.studentUid) {
                        it.copy(isCheckedIn = newIsCheckedIn, checkInTime = newCheckInTimeStr)
                    } else {
                        it
                    }
                }

                val newPresentCount = updatedStudentsList.count { it.isCheckedIn }
                val updatedSession = session.copy(
                    present = newPresentCount,
                    studentsList = updatedStudentsList
                )

                selectedSessionForDialog = updatedSession
                sessionsList = sessionsList.map {
                    if (it.sessionId == session.sessionId) updatedSession else it
                }
            } catch (e: Exception) {
                errorMessage = "Failed to update attendance: ${e.localizedMessage}"
            } finally {
                isUpdatingAttendance = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadNextPage()
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
        if (isInitialLoading) {
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
                if (errorMessage != null && sessionsList.isEmpty()) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { loadNextPage() }) {
                                Text("Retry")
                            }
                        }
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

                    // Pagination Footer Item
                    item {
                        if (isPageLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        } else if (hasMoreSessions) {
                            OutlinedButton(
                                onClick = { loadNextPage() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Text("Load 10 more")
                            }
                        }
                    }
                }
            }
        }

        // Attendance Modal Dialog Window
        if (selectedSessionForDialog != null) {
            val session = selectedSessionForDialog!!
            val isEditable = session.sessionId == latestEndedSessionId

            AlertDialog(
                onDismissRequest = { selectedSessionForDialog = null },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Attendance",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (isEditable) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "Editable",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
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

                                        // Editing control for latest ended session
                                        if (isEditable) {
                                            if (student.isCheckedIn) {
                                                TextButton(
                                                    onClick = { toggleStudentAttendance(session, student) },
                                                    enabled = !isUpdatingAttendance
                                                ) {
                                                    Text(
                                                        "Mark Absent",
                                                        color = MaterialTheme.colorScheme.error,
                                                        style = MaterialTheme.typography.labelMedium
                                                    )
                                                }
                                            } else {
                                                Button(
                                                    onClick = { toggleStudentAttendance(session, student) },
                                                    enabled = !isUpdatingAttendance,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Text(
                                                        "Mark Present",
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
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
