package com.greenchat.webdextro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Dialog components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun PasswordPromptDialog(
    show: Boolean,
    channelName: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show && channelName != null) {
        val colorScheme = MaterialTheme.colorScheme
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "Enter Channel Password",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Channel $channelName is password protected. Enter the password to join.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text("Password", style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = "Join",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}

@Composable
fun AppInfoDialog(
    show: Boolean,
    onDismiss: () -> Unit
) {
    if (show) {
        val colorScheme = MaterialTheme.colorScheme
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "About GreenChat*",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Secure, private messaging even when you're offline or in remote areas.\n\n" +
                            "• ✅ No internet or SIM card required\n" +
                            "• 📡 Bluetooth-based mesh messaging system\n" +
                            "• 🔒 End-to-end encrypted private messages\n" +
                            "• 🛰️ Works anywhere in the World\n" +
                            "• ⏩ Store-and-forward for offline peers\n\n" +
                            "Decentralized messaging technology developed by Muntasir Mubin.\n\n " +
                            "Triple-click title to emergency reset all data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}
