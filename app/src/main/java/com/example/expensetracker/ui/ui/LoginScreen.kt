package com.example.expensetracker.ui.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    authVm: AuthViewModel = hiltViewModel(),
    onLoggedIn: () -> Unit
) {
    val uiState = authVm.uiState
    val tabs = listOf("Email", "Phone")

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) onLoggedIn()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Expense Tracker") }) }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            TabRow(selectedTabIndex = uiState.selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTab == index,
                        onClick = { authVm.updateSelectedTab(index) },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            when (uiState.selectedTab) {
                0 -> EmailTab(authVm)
                1 -> PhoneTab(authVm)
            }

            uiState.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun EmailTab(vm: AuthViewModel) {
    val uiState = vm.uiState

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = uiState.email,
            onValueChange = { vm.updateEmail(it) },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        OutlinedTextField(
            value = uiState.pass,
            onValueChange = { vm.updatePass(it) },
            label = { Text("Password (min 6)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        Button(
            onClick = {
                if (uiState.isRegisterMode) vm.registerEmail()
                else vm.loginEmail()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading && uiState.email.isNotBlank() && uiState.pass.length >= 6
        ) {
            if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (uiState.isRegisterMode) "Create Account" else "Login")
        }

        TextButton(onClick = { vm.toggleRegisterMode() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.isRegisterMode) "Already have account? Login" else "No account? Register")
        }
    }
}

@Composable
fun PhoneTab(vm: AuthViewModel) {
    val context = LocalContext.current
    val activity = context as Activity
    val uiState = vm.uiState

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = uiState.phone,
            onValueChange = { vm.updatePhone(it) },
            label = { Text("Phone e.g. +27731234567") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            enabled = !uiState.codeSent
        )

        if (!uiState.codeSent) {
            Button(
                onClick = { vm.sendOtp(activity) },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.phone.length >= 10
            ) { Text("Send OTP") }
        } else {
            OutlinedTextField(
                value = uiState.otp,
                onValueChange = { vm.updateOtp(it) },
                label = { Text("6-digit OTP") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Button(
                onClick = { vm.verifyOtp() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading && uiState.otp.length == 6
            ) {
                if (uiState.isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Verify & Login")
            }
            TextButton(onClick = { vm.resetPhoneState() }) { Text("Change number") }
        }
    }
}
