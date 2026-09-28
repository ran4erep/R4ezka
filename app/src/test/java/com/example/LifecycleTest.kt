package com.example

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RezkaApplication::class, sdk = [34])
class LifecycleTest {

    @Test
    fun testBackPressAndReopen() {
        println("=== STEP 1: Launching first MainActivity ===")
        val controller1 = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity1 = controller1.get()
        println("=== STEP 2: Pressing Back on first MainActivity ===")
        activity1.onBackPressedDispatcher.onBackPressed()
        println("Activity1 isFinishing=${activity1.isFinishing}")
        
        controller1.pause().stop().destroy()
        println("=== STEP 3: Launching second MainActivity in same process ===")
        val controller2 = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity2 = controller2.get()
        println("Activity2 state=${activity2.lifecycle.currentState}")
        controller2.pause().stop().destroy()
        println("=== TEST SUCCESSFUL ===")
    }
}
