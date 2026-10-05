package com.example.classcheck.utils

fun getAuthErrorMessage(e: Exception): String {
    val message = e.localizedMessage ?: ""
    return when {
        message.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) || message.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) -> 
            "Email/Password sign-in is not enabled in your Firebase Console. Please enable Email/Password under Firebase Console > Authentication > Sign-in method."
        message.contains("password", ignoreCase = true) || message.contains("credential", ignoreCase = true) -> 
            "Incorrect password or email."
        message.contains("no user record", ignoreCase = true) || message.contains("user-not-found", ignoreCase = true) -> 
            "Account not found."
        message.contains("email-already-in-use", ignoreCase = true) -> 
            "Email is already registered."
        message.contains("network", ignoreCase = true) -> 
            "Network error. Please check your connection."
        else -> message.ifEmpty { "An unexpected error occurred. Please try again." }
    }
}
