package app.tally

import android.app.Application
import app.tally.data.ExpenseStore

class TallyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ExpenseStore.init(this)
    }
}
