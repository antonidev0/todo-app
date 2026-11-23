package com.example.todo_app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.todo_app.reminders.ReminderScheduler
import com.example.todo_app.ui.TaskScreen
import com.example.todo_app.ui.TaskViewModel
import com.example.todo_app.ui.theme.TodoappTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TodoappTheme {
                val vm: TaskViewModel = viewModel()
                TaskScreen(vm, topBanner = { PermissionBanner() })
            }
        }
    }
}

/** Pide el permiso de notificaciones y avisa si faltan permisos para los recordatorios. */
@Composable
private fun PermissionBanner() {
    val context = LocalContext.current

    fun notificationsAllowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    var notifOk by remember { mutableStateOf(notificationsAllowed()) }
    var exactOk by remember { mutableStateOf(ReminderScheduler.canScheduleExact(context)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifOk = it
    }

    LaunchedEffect(Unit) {
        if (!notifOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Al volver de Ajustes, comprobamos otra vez.
    LifecycleResumeEffect(Unit) {
        notifOk = notificationsAllowed()
        exactOk = ReminderScheduler.canScheduleExact(context)
        onPauseOrDispose { }
    }

    if (!notifOk) {
        Banner("Las notificaciones están desactivadas, así que los recordatorios no se verán.") {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    } else if (!exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Banner("Permite las alarmas exactas para que los recordatorios suenen a su hora.") {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
            )
        }
    }
}

@Composable
private fun Banner(text: String, onFix: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onFix) { Text("Abrir ajustes") }
        }
    }
}
