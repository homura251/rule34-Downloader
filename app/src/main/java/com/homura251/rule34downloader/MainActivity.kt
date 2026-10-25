package com.homura251.rule34downloader

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.homura251.rule34downloader.work.DownloadNotifications
import androidx.core.content.ContextCompat
import com.homura251.rule34downloader.ui.DownloaderApp
import com.homura251.rule34downloader.ui.theme.Rule34DownloaderTheme

class MainActivity : ComponentActivity() {
    private var failureAuthor by mutableStateOf<String?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        failureAuthor = intent.getStringExtra(DownloadNotifications.EXTRA_FAILURE_AUTHOR)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        failureAuthor = intent.getStringExtra(DownloadNotifications.EXTRA_FAILURE_AUTHOR)
        enableEdgeToEdge()
        setContent {
            Rule34DownloaderTheme {
                AppContent()
            }
        }
    }

    @Composable
    private fun AppContent() {
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }

        DownloaderApp(
            services = (application as Rule34Application).graph,
            failureAuthor = failureAuthor,
            onFailureNavigationHandled = { failureAuthor = null; intent.removeExtra(DownloadNotifications.EXTRA_FAILURE_AUTHOR) },
            onRequestNotificationPermission = {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
}
