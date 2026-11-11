package com.example.expensetracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.annotation.RequiresApi
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.expensetracker.data.NavigationType
import com.example.expensetracker.data.notifications.NotificationHelper
import com.example.expensetracker.data.notifications.scheduleDailyReminder
import com.example.expensetracker.data.notifications.scheduleWeeklySummary
import com.example.expensetracker.theme.ExpenseTrackerTheme
import com.example.expensetracker.ui.ui.ExpenseApp
import com.example.expensetracker.ui.ui.LoginScreen
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.P)
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            var isLoggedIn by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }

            ExpenseTrackerTheme {
                if (!isLoggedIn) {
                    LoginScreen(onLoggedIn = {isLoggedIn = true})
                } else {
                    ExpenseApp(
                        windowSizeClass = windowSizeClass,
                        onLogout = { isLoggedIn = false }
                    )
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    scheduleDailyReminder(this@MainActivity)
                    scheduleWeeklySummary(this@MainActivity, true)
                    NotificationHelper(this@MainActivity)
                }
            } else {
                scheduleDailyReminder(this@MainActivity)
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ExpenseTrackerTheme {
        Greeting("Android")
    }
}