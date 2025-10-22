package com.criipto.verifyexample

import androidx.test.core.app.takeScreenshot
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.textAsString
import androidx.test.uiautomator.uiAutomator
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockLoginTest {
  @get:Rule
  var screenshotWatcher = ScreenshotTestRule()

  @Test
  fun runMockLogin() =
    uiAutomator {
      startApp("com.criipto.verifyexample")
      onElement { textAsString() == "Login with Mock" }.click()

      onElement { textAsString() == "Logged in!" }
    }
}

class ScreenshotTestRule : TestWatcher() {
  override fun failed(
    e: Throwable?,
    description: Description?,
  ) {
    super.failed(e, description)
    println("failed!")

    val className = description?.testClass?.simpleName ?: "NullClassname"
    val methodName = description?.methodName ?: "NullMethodName"

    takeScreenshot().writeToTestStorage("${className}_$methodName")
  }
}
