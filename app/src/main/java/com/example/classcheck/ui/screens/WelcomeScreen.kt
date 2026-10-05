package com.example.classcheck.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.classcheck.navigation.Screen
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

@Composable
fun WelcomeScreen(navController: NavController) {
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            try {
                val db = FirebaseFirestore.getInstance()
                val snapshot = db.collection("users").document(currentUser.uid).get().await()
                if (snapshot.exists()) {
                    val role = snapshot.getString("role")
                    when (role) {
                        "student" -> {
                            navController.navigate(Screen.StudentHome.route) {
                                popUpTo(Screen.Welcome.route) { inclusive = true }
                            }
                            return@LaunchedEffect
                        }
                        "teacher" -> {
                            navController.navigate(Screen.TeacherHome.route) {
                                popUpTo(Screen.Welcome.route) { inclusive = true }
                            }
                            return@LaunchedEffect
                        }
                        else -> {
                            errorMessage = "Invalid user role found."
                            FirebaseAuth.getInstance().signOut()
                        }
                    }
                } else {
                    errorMessage = "User profile not found in database."
                    FirebaseAuth.getInstance().signOut()
                }
            } catch (e: Exception) {
                errorMessage = "Failed to verify session: ${e.localizedMessage}"
                FirebaseAuth.getInstance().signOut()
            }
        }
        isLoading = false
    }

    Scaffold { padding ->
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "ClassCheck",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Simple & reliable attendance\nfor university classes",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = { navController.navigate(Screen.StudentAuth.route) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Continue as Student")
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { navController.navigate(Screen.TeacherLogin.route) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Continue as Teacher")
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Prototype / Mockup version",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
