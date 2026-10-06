package com.example.classcheck.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.classcheck.navigation.Screen
import com.example.classcheck.utils.getAuthErrorMessage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentLoginScreen(navController: NavController) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Student Login") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Sign in as Student",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Use your @va.lv student email",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email (@va.lv)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(image, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val trimmedEmail = email.trim()
                    if (trimmedEmail.isEmpty() || password.isEmpty()) {
                        errorMessage = "Please fill in all fields."
                        return@Button
                    }
                    if (!trimmedEmail.lowercase().endsWith("@va.lv")) {
                        errorMessage = "Email must end with @va.lv"
                        return@Button
                    }

                    isLoading = true
                    errorMessage = null

                    scope.launch {
                        try {
                            val auth = FirebaseAuth.getInstance()
                            val result = auth.signInWithEmailAndPassword(trimmedEmail, password).await()
                            val uid = result.user?.uid ?: throw Exception("Authentication failed.")

                            val db = FirebaseFirestore.getInstance()
                            val doc = db.collection("users").document(uid).get().await()

                            if (!doc.exists()) {
                                auth.signOut()
                                errorMessage = "User profile document not found in Firestore."
                                isLoading = false
                                return@launch
                            }

                            val role = doc.getString("role")
                            if (role != "student") {
                                auth.signOut()
                                errorMessage = "Access denied: this account is not registered as a student."
                                isLoading = false
                                return@launch
                            }

                            // Ensure classStudents assignments exist
                            val program = doc.getString("program") ?: ""
                            val studentName = doc.getString("name") ?: "Student"
                            val studentId = doc.getString("studentId") ?: ""
                            if (program.isNotEmpty()) {
                                val classesSnap = db.collection("classes")
                                    .whereEqualTo("program", program)
                                    .get()
                                    .await()

                                for (cDoc in classesSnap.documents) {
                                    val cId = cDoc.id
                                    val assignmentMap = hashMapOf(
                                        "classId" to cId,
                                        "studentUid" to uid,
                                        "studentId" to studentId,
                                        "studentName" to studentName
                                    )
                                    db.collection("classStudents").document("${cId}_$uid").set(assignmentMap, SetOptions.merge()).await()
                                }
                            }

                            navController.navigate(Screen.StudentHome.route) {
                                popUpTo(Screen.Welcome.route) { inclusive = true }
                            }
                        } catch (e: Exception) {
                            errorMessage = getAuthErrorMessage(e)
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Login")
                }
            }
        }
    }
}
