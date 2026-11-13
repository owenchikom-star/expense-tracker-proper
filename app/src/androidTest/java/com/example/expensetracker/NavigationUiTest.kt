package com.example.expensetracker

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.example.expensetracker.theme.ExpenseTrackerTheme
import com.example.expensetracker.ui.ui.ExpenseApp
import org.junit.Rule
import org.junit.Test

class NavigationUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun appStartsOnDashboardTab() {
        composeTestRule.setContent {
            val windowSizeClass = calculateWindowSizeClass(composeTestRule.activity)
            ExpenseTrackerTheme {
                ExpenseApp(
                    windowSizeClass = windowSizeClass
                )
            }
        }

        // Dashboard title should be visible
        composeTestRule.onNodeWithText("Spending").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun canSwitchToTransactionsTab() {
        composeTestRule.setContent {
            val windowSizeClass = calculateWindowSizeClass(composeTestRule.activity)
            ExpenseTrackerTheme {
                ExpenseApp(
                    windowSizeClass = windowSizeClass
                )
            }
        }

        // Click on Transactions tab
        composeTestRule.onNodeWithText("Transactions").performClick()

        // Search bar or some text from the transactions screen should be visible
        composeTestRule.onNodeWithText("Search expense…").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun clickingFABOpensAddExpenseSheet() {
        composeTestRule.setContent {
            val windowSizeClass = calculateWindowSizeClass(composeTestRule.activity)
            ExpenseTrackerTheme {
                ExpenseApp(
                    windowSizeClass = windowSizeClass
                )
            }
        }

        // Switch to Transactions tab where FAB is located
        composeTestRule.onNodeWithText("Transactions").performClick()

        // Find FAB by content description
        composeTestRule.onNodeWithContentDescription("add expense", ignoreCase = true).performClick()

        // "Add Expense" title in the sheet should be visible
        composeTestRule.onNodeWithText("Add Expense").assertIsDisplayed()
    }
}
