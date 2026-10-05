package com.example.classcheck.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.classcheck.ui.screens.*

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object StudentAuth : Screen("student_auth")
    object StudentLogin : Screen("student_login")
    object StudentRegister : Screen("student_register")
    object TeacherLogin : Screen("teacher_login")
    object StudentHome : Screen("student_home")
    object CheckIn : Screen("check_in")
    object CheckInSuccess : Screen("check_in_success")
    object AttendanceHistory : Screen("attendance_history")
    object Profile : Screen("profile")
    object TeacherHome : Screen("teacher_home")
    object ActiveSession : Screen("active_session")
    object SessionHistory : Screen("session_history")
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Welcome.route
    ) {
        composable(Screen.Welcome.route) {
            WelcomeScreen(navController)
        }
        composable(Screen.StudentAuth.route) {
            StudentAuthScreen(navController)
        }
        composable(Screen.StudentLogin.route) {
            StudentLoginScreen(navController)
        }
        composable(Screen.StudentRegister.route) {
            StudentRegisterScreen(navController)
        }
        composable(Screen.TeacherLogin.route) {
            TeacherLoginScreen(navController)
        }
        composable(Screen.StudentHome.route) {
            StudentHomeScreen(navController)
        }
        composable(Screen.CheckIn.route) {
            CheckInScreen(navController)
        }
        composable(Screen.CheckInSuccess.route) {
            CheckInSuccessScreen(navController)
        }
        composable(Screen.AttendanceHistory.route) {
            AttendanceHistoryScreen(navController)
        }
        composable(Screen.Profile.route) {
            ProfileScreen(navController)
        }
        composable(Screen.TeacherHome.route) {
            TeacherHomeScreen(navController)
        }
        composable(Screen.ActiveSession.route) {
            ActiveSessionScreen(navController)
        }
        composable(Screen.SessionHistory.route) {
            SessionHistoryScreen(navController)
        }
    }
}
