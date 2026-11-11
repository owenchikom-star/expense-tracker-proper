package com.example.expensetracker.data

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.vertexai.vertexAI

class AppContainer(private val context: Context) {
    val currencyManager: CurrencyManager by lazy {
        CurrencyManager(context, context.getSharedPreferences("expense_prefs", Context.MODE_PRIVATE))
    }

    val expenseRepository: ExpenseRepository by lazy {
        RoomExpenseRepository(
            ExpenseDatabase.getDatabase(context).expenseDao(),
            FirebaseAuth.getInstance(),
            FirebaseFirestore.getInstance(),
            context
        )
    }

    val categoryBudgetRepository: CategoryBudgetRepository by lazy {
        RoomCategoryBudgetRepository(
            ExpenseDatabase.getDatabase(context).categoryBudgetDao(),
            ExpenseDatabase.getDatabase(context).expenseDao(),
            FirebaseAuth.getInstance(),
            FirebaseFirestore.getInstance(),
            context
        )
    }

    val receiptParser: ReceiptParser by lazy {
        ReceiptParser(Firebase.vertexAI.generativeModel("gemini-2.0-flash"))
    }
}
