package com.ekstraktranskrip

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ekstraktranskrip.ui.AppRoot
import com.ekstraktranskrip.ui.theme.EkstrakTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val jobRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        jobRequest.value = intent?.getStringExtra(EXTRA_JOB_ID)

        setContent {
            EkstrakTheme {
                val request by jobRequest.collectAsState()
                AppRoot(
                    jobRequest = request,
                    onJobRequestConsumed = { jobRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        jobRequest.value = intent.getStringExtra(EXTRA_JOB_ID)
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"
    }
}
