package ai.champi.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/** Custom [AndroidJUnitRunner] that swaps in [HiltTestApplication] for instrumented tests. */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
