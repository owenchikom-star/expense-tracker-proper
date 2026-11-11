package com.example.expensetracker

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.expensetracker.theme.ExpenseTrackerTheme
import com.example.expensetracker.ui.ui.ExpenseApp
import com.google.firebase.auth.FirebaseAuth
import org.junit.Rule
import org.junit.Test

class ExpenseEntryFormUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()


    @Test
    fun saveButtonIsDisabledWhenFieldsAreEmpty() {
        composeTestRule.setContent {
            ExpenseTrackerTheme {
                ExpenseApp(
                    windowSizeClass = TODO(),
                    modifier = TODO(),
                    onLogout = TODO(),
                    authViewModel = TODO(),
                    viewModel = TODO()
                )
            }
        }

        // Switch to Transactions tab and open Add sheet
        composeTestRule.onNodeWithText("Transactions").performClick()
        composeTestRule.onNodeWithContentDescription("add expense", ignoreCase = true).performClick()

        // Initially name and price are empty, so Save button should be disabled
        // We find the Save button by text "Save" and it should be a Button
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun saveButtonIsEnabledWhenFieldsAreFilled() {
        composeTestRule.setContent {
            ExpenseTrackerTheme {
                ExpenseApp(
                    windowSizeClass = TODO(),
                    modifier = TODO(),
                    onLogout = TODO(),
                    authViewModel = TODO(),
                    viewModel = TODO()
                )
            }
        }

        // Open Add sheet
        composeTestRule.onNodeWithText("Transactions").performClick()
        composeTestRule.onNodeWithContentDescription("add expense", ignoreCase = true).performClick()

        // Fill Name
        composeTestRule.onNodeWithText("Expense Name").performTextInput("Lunch")
        
        // Fill Price
        composeTestRule.onNodeWithText("Amount").performTextInput("150")

        // Save button should now be enabled
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun typingNamePredictsCategory() {
        composeTestRule.setContent {
            ExpenseTrackerTheme {
                ExpenseApp(
                    windowSizeClass = TODO(),
                    modifier = TODO(),
                    onLogout = TODO(),
                    authViewModel = TODO(),
                    viewModel = TODO()
                )
            }
        }

        // Open Add sheet
        composeTestRule.onNodeWithText("Transactions").performClick()
        composeTestRule.onNodeWithContentDescription("add expense", ignoreCase = true).performClick()

        // Fill Name with "Uber"
        composeTestRule.onNodeWithText("Expense Name").performTextInput("Uber")

        // Verify "Transport" category chip is selected
        // We use hasAnyAncestor(isPopup()) or similar if it was a popup, 
        // but here it's just two sets of chips. 
        // We can look for the one that IS selected.
        composeTestRule.onNode(
            hasText("Transport") and isSelected()
        ).assertExists()
    }
}
