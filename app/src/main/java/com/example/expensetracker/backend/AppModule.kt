package com.example.expensetracker.backend

import android.content.Context
import com.example.expensetracker.data.CategoryBudgetDao
import com.example.expensetracker.data.CategoryBudgetRepository
import com.example.expensetracker.data.CurrencyManager
import com.example.expensetracker.data.ExpenseDao
import com.example.expensetracker.data.ExpenseDatabase
import com.example.expensetracker.data.ExpenseRepository
import com.example.expensetracker.data.RoomCategoryBudgetRepository
import com.example.expensetracker.data.RoomExpenseRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Firebase
import com.google.firebase.vertexai.GenerativeModel
import com.google.firebase.vertexai.vertexAI
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGenerativeModel(): GenerativeModel {
        return Firebase.vertexAI.generativeModel("gemini-2.0-flash")
    }

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = Firebase.auth

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseStorage(): com.google.firebase.storage.FirebaseStorage {
        val storage = com.google.firebase.storage.FirebaseStorage.getInstance()
        storage.maxUploadRetryTimeMillis = 30_000L
        storage.maxOperationRetryTimeMillis = 30_000L
        return storage
    }

    @Provides
    @Singleton
    fun provideExpenseDatabase(@ApplicationContext context: Context): ExpenseDatabase =
        ExpenseDatabase.getDatabase(context)

    @Provides
    @Singleton
    fun provideExpenseDao(database: ExpenseDatabase): ExpenseDao =
        database.expenseDao()

    @Provides
    @Singleton
    fun provideCategoryBudgetDao(database: ExpenseDatabase): CategoryBudgetDao =
        database.categoryBudgetDao()

    @Provides
    @Singleton
    fun provideExpenseRepository(impl: RoomExpenseRepository): ExpenseRepository = impl

    @Provides
    @Singleton
    fun provideCategoryBudgetRepository(
        database: ExpenseDatabase,
        auth: FirebaseAuth,
        firestore: FirebaseFirestore,
        @ApplicationContext context: Context,
    ): CategoryBudgetRepository = RoomCategoryBudgetRepository(
        database.categoryBudgetDao(),
        database.expenseDao(),
        auth,
        firestore,
        context
    )

    @Provides
    @Singleton
    fun provideCurrencyManager(@ApplicationContext context: Context): CurrencyManager =
        CurrencyManager(context, context.getSharedPreferences("expense_prefs", Context.MODE_PRIVATE))
}
