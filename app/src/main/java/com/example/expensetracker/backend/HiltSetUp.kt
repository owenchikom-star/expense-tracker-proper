package com.example.expensetracker.backend

import android.app.Application
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import dagger.hilt.android.HiltAndroidApp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class ExpenseTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this@ExpenseTrackerApp)
                if (availability == ConnectionResult.SUCCESS) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance()
                    )
                } else {
                    Log.w("ExpenseTrackerApp", "Google Play Services not available: $availability")
                }
            } catch (e: Exception) {
                Log.e("ExpenseTrackerApp", "Failed to initialize AppCheck", e)
            }
        }
    }
}