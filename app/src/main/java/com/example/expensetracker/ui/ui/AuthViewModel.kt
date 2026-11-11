package com.example.expensetracker.ui.ui

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.AuthRepository
import com.example.expensetracker.data.ExpenseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val expenseRepo: ExpenseRepository
) : ViewModel() {

    var uiState by mutableStateOf(AuthUiState())
        private set

    var verificationId by mutableStateOf<String?>(null)
        private set

    fun updateSelectedTab(tab: Int) {
        uiState = uiState.copy(selectedTab = tab, error = null)
    }

    fun updateEmail(email: String) {
        uiState = uiState.copy(email = email)
    }

    fun updatePass(pass: String) {
        uiState = uiState.copy(pass = pass)
    }

    fun toggleRegisterMode() {
        uiState = uiState.copy(isRegisterMode = !uiState.isRegisterMode, error = null)
    }

    fun updatePhone(phone: String) {
        uiState = uiState.copy(phone = phone)
    }

    fun updateOtp(otp: String) {
        uiState = uiState.copy(otp = otp)
    }

    fun resetPhoneState() {
        uiState = uiState.copy(codeSent = false, otp = "", error = null)
    }

    fun loginEmail() {
        login(uiState.email.trim(), uiState.pass)
    }

    fun registerEmail() {
        register(uiState.email.trim(), uiState.pass)
    }

    fun login(email: String, pass: String) {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, error = null)
            try {
                authRepo.loginEmail(email, pass)
                expenseRepo.startRealtimeSync()
                try {
                    expenseRepo.syncFromCloud()
                } catch (e: Exception) {
                    android.util.Log.e("AuthViewModel", "syncFromCloud failed on login", e)
                }
                uiState = uiState.copy(isLoading = false, isLoggedIn = true)
            } catch (e: Exception) {
                uiState = uiState.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun register(email: String, pass: String) {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, error = null)
            try {
                authRepo.registerEmail(email, pass)
                expenseRepo.startRealtimeSync()
                try {
                    expenseRepo.syncFromCloud()
                } catch (e: Exception) {
                    android.util.Log.e("AuthViewModel", "syncFromCloud failed on register", e)
                }
                uiState = uiState.copy(isLoading = false, isLoggedIn = true)
            } catch (e: Exception) {
                uiState = uiState.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun sendOtp(activity: Activity) {
        val phone = uiState.phone.trim()
        if (phone.length < 10) return
        uiState = uiState.copy(isLoading = true, error = null)
        authRepo.sendOtp(
            phoneNumber = phone,
            activity = activity,
            onCodeSent = { id ->
                verificationId = id
                uiState = uiState.copy(isLoading = false, codeSent = true)
            },
            onError = { err ->
                uiState = uiState.copy(isLoading = false, error = err)
            }
        )
    }

    fun onCodeSent(id: String) {
        verificationId = id
        uiState = uiState.copy(codeSent = true)
    }

    fun verifyOtp(otp: String = uiState.otp) {
        val verId = verificationId ?: return
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, error = null)
            try {
                authRepo.verifyOtp(verId, otp.trim())
                expenseRepo.startRealtimeSync()
                try {
                    expenseRepo.syncFromCloud()
                } catch (e: Exception) {
                    android.util.Log.e("AuthViewModel", "syncFromCloud failed on verifyOtp", e)
                }
                uiState = uiState.copy(isLoading = false, isLoggedIn = true)
            } catch (e: Exception) {
                uiState = uiState.copy(isLoading = false, error = "Invalid OTP")
            }
        }
    }

    fun logout(onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                expenseRepo.logoutCleanup()
                authRepo.logout()
                uiState = AuthUiState()
                onSuccess?.invoke()
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "Logout failed", e)
            }
        }
    }
}

data class AuthUiState(
    val selectedTab: Int = 0,
    val email: String = "",
    val pass: String = "",
    val isRegisterMode: Boolean = false,
    val phone: String = "",
    val otp: String = "",
    val codeSent: Boolean = false,
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null
)
