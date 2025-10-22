package com.criipto.verify

import android.util.Log
import androidx.activity.ComponentActivity

/**
 * This activity handles app switching from the Danish MitID app.
 *
 * Once the user has approved in the MitID app, MitID will trigger an app link without any OAuth
 * parameters. The purpose of this request is only to return control to your app. Therefore, we
 * finish the activity as soon as it is resume, and let the OAuth flow continue in the browser.
 */
class AppSwitchActivity : ComponentActivity() {
  override fun onResume() {
    Log.i("AppSwitchActivity", "Handling app switch")
    super.onResume()
    finish()
  }
}
