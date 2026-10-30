package com.projectnyanko.companion.ui.stage

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.projectnyanko.companion.domain.stage.StageLocale

/** Small transparent trampoline for Android's per-session screen-share consent dialog. */
class StageCapturePermissionActivity : ComponentActivity() {
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val region = intent.getStringExtra(StageOverlayService.EXTRA_REGION) ?: DEFAULT_REGION
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val locale = if (region.equals("JP", ignoreCase = true)) StageLocale.JP else StageLocale.EN
            StageCaptureService.enable(this, result.resultCode, result.data!!, locale)
        } else {
            StageOverlayService.showMessage(this, region, "Screen capture was cancelled. Tap the bubble to try again.")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.setLayout(1, 1)
        window.setGravity(Gravity.TOP or Gravity.START)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.decorView.alpha = 0f
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            captureConsent.launch(manager.createScreenCaptureIntent())
        }
    }

    private companion object {
        const val DEFAULT_REGION = "EN"
    }
}
