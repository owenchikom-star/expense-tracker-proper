package com.example.expensetracker

import com.example.expensetracker.data.CategoryBudget
import com.example.expensetracker.data.CategoryBudgetRepository
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.ExpenseRepository
import com.example.expensetracker.data.MonthlyReport
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class FakeExpenseRepository : ExpenseRepository {
    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    override fun getAllExpenses(): Flow<List<Expense>> = _expenses

    override suspend fun insert(expense: Expense) {
        val newExpense = if (expense.id.isBlank()) expense.copy(id = java.util.UUID.randomUUID().toString()) else expense
        _expenses.value = _expenses.value + newExpense
    }

    override suspend fun upsert(expense: Expense): String {
        val finalExpense = if (expense.id.isBlank()) expense.copy(id = java.util.UUID.randomUUID().toString()) else expense
        val exists = _expenses.value.any { it.id == finalExpense.id }
        if (exists) {
            _expenses.value = _expenses.value.map { if (it.id == finalExpense.id) finalExpense else it }
        } else {
            _expenses.value = _expenses.value + finalExpense
        }
        return finalExpense.id
    }

    override suspend fun delete(expense: Expense) {
        _expenses.value = _expenses.value.filter { it.id != expense.id }
    }

    override suspend fun update(expense: Expense) { upsert(expense) }

    override fun getExpensesByDateRange(startDate: Long, endDate: Long): Flow<List<Expense>> =
        _expenses.map { it.filter { e -> e.date.toEpochDay() in startDate..endDate } }

    override fun getExpensesByCategory(category: String): Flow<List<Expense>> =
        _expenses.map { it.filter { e -> e.category == category } }

    override fun getTotalBetweenDates(startDate: Long, endDate: Long): Flow<Double?> =
        getExpensesByDateRange(startDate, endDate).map { it.sumOf { e -> e.price } }

    override fun getFilteredExpenses(category: String, startMs: Long?, endMs: Long?): Flow<List<Expense>> = _expenses
    override fun getTotalForDate(startOfDay: Long, endOfDay: Long): Float = 0f
    override suspend fun getTotalForWeek(startOfWeek: Long, endOfWeek: Long): Float = 0f
    override suspend fun getTotalByCategoryAndMonth(category: String, start: Long, end: Long): Float = 0f
    override suspend fun addExpense(expense: Expense) { insert(expense) }
    override suspend fun syncFromCloud() {}
    override suspend fun pushUnsynced() {}
    override suspend fun logoutCleanup() { clearAll() }
    override fun startRealtimeSync(onUpdated: () -> Unit): ListenerRegistration {
        return ListenerRegistration { }
    }
    override suspend fun clearAll() { _expenses.value = emptyList() }
    override suspend fun insertAll(expenses: List<Expense>) { _expenses.value = _expenses.value + expenses }
    override suspend fun deleteAll(id: String) { _expenses.value = _expenses.value.filter { it.id != id } }
}

class FakeCategoryBudgetRepository : CategoryBudgetRepository {
    private val _budgets = MutableStateFlow<List<CategoryBudget>>(emptyList())
    val onTransactionAddedCalls = mutableListOf<Expense>()

    override fun getBudgetForMonth(month: Int, year: Int): Flow<List<CategoryBudget>> =
        _budgets.map { it.filter { b -> b.month == month && b.year == year } }

    override suspend fun upsertBudget(budget: CategoryBudget) {
        _budgets.value = _budgets.value.filterNot {
            it.category == budget.category && it.month == budget.month && it.year == budget.year
        } + budget
    }
    override suspend fun insertBudget(budget: CategoryBudget) = upsertBudget(budget)
    override suspend fun deleteBudget(category: String, month: Int, year: Int) {
        _budgets.value = _budgets.value.filterNot {
            it.category == category && it.month == month && it.year == year
        }
    }
    override suspend fun getBudgetForOneMonth(category: String, month: Int, year: Int): Double? =
        _budgets.value.find { it.category == category && it.month == month && it.year == year }?.monthlyBudget
    override suspend fun onTransactionAdded(expense: Expense) {
        onTransactionAddedCalls.add(expense)
    }

    override suspend fun copyLastMonthBudgets(targetMonth: Int, targetYear: Int) {
        val lastMonthDate = LocalDate.of(targetYear, targetMonth, 1).minusMonths(1)
        val lastMonthBudgets = _budgets.value.filter {
            it.month == lastMonthDate.monthValue && it.year == lastMonthDate.year
        }
        lastMonthBudgets.forEach { old ->
            upsertBudget(old.copy(id = 0, month = targetMonth, year = targetYear))
        }
    }

    override suspend fun getMonthlyReportData(month: Int, year: Int): MonthlyReport {
        return MonthlyReport(
            month = month,
            year = year,
            totalSpent = 0.0,
            totalBudget = 0.0,
            spentByCategory = emptyMap<String, Double>(),
            budgetByCategory = emptyMap<String, Double>(),
            topExpenses = emptyList<Expense>()
        )
    }

    override fun startRealtimeSync(): Job = Job()
    override suspend fun syncFromCloud() {}
    override suspend fun pushUnsynced() {}
}