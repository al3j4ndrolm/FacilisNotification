package io.github.al3j4ndrolm.facilis.notification.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.al3j4ndrolm.facilis.notification.notify
import io.github.al3j4ndrolm.facilis.permission.Permission
import io.github.al3j4ndrolm.facilis.permission.rememberPermission
import io.github.al3j4ndrolm.facilis.storage.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Taps and app-opening buttons: the app is open, so register them here.
        notify.onTap { data -> log("Tapped${data["course"]?.let { ": $it" }.orEmpty()}") }
        notify.onAction("Directions") { data -> log("Directions to ${data["room"]}") }

        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SampleScreen() }
            }
        }
    }
}

@Composable
private fun SampleScreen() {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    // Refresh the scheduled list now and then.
    LaunchedEffect(Unit) { while (true) { delay(2_000); refresh++ } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("FacilisNotification", style = MaterialTheme.typography.headlineMedium)

        val permission = rememberPermission(Permission.Notifications)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications: ${permission.status}", modifier = Modifier.weight(1f))
            if (!permission.granted) TextButton(onClick = { permission.request() }) { Text("Allow") }
        }
        HorizontalDivider()

        Section("Show")
        Button(onClick = { notify("Coffee is ready ☕") }, modifier = Modifier.fillMaxWidth()) {
            Text("notify(\"Coffee is ready\")")
        }
        Button(
            onClick = {
                notify(
                    title = "CS 149 starts in 15 minutes",
                    text = "Room MQH 225",
                    data = mapOf("classId" to "cs149", "course" to "CS 149", "room" to "MQH 225"),
                    actions = classActions,
                    channel = "Classes",
                    key = "class-cs149",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Class reminder with 3 buttons") }
        Button(
            onClick = {
                scope.launch {
                    val upload = notify.progress("Uploading photo…")
                    for (step in 1..10) {
                        delay(500)
                        upload.progress = step / 10f
                    }
                    upload.done("Photo uploaded ✓")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Upload with progress") }

        HorizontalDivider()
        Section("Schedule")
        Button(
            onClick = { notify.after(1.minutes, "1 minute is up ⏱", key = "one-minute"); refresh++ },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("notify.after(1.minutes)") }
        Button(
            onClick = {
                notify.every(
                    at = LocalTime.of(9, 30),
                    days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                    title = "CS 149 starts in 15 minutes",
                    text = "Room MQH 225",
                    data = mapOf("classId" to "cs149", "course" to "CS 149", "room" to "MQH 225"),
                    actions = classActions,
                    channel = "Classes",
                    key = "class-cs149-weekly",
                )
                refresh++
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Every Mon & Wed at 9:30") }

        val scheduled = remember(refresh) { notify.scheduled() }
        if (scheduled.isEmpty()) Text("Nothing scheduled", style = MaterialTheme.typography.bodySmall)
        scheduled.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${item.key} · next ${item.nextTime.format(DateTimeFormatter.ofPattern("EEE MMM d, HH:mm:ss"))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { notify.cancelScheduled(item.key); refresh++ }) { Text("Cancel") }
            }
        }
        OutlinedButton(onClick = { notify.cancelAll(); refresh++ }, modifier = Modifier.fillMaxWidth()) {
            Text("Remove all shown notifications")
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Section("Events", Modifier.weight(1f))
            TextButton(onClick = { eventLog.clear() }) { Text("Clear") }
        }
        val events by eventLog.collectAsState()
        if (events.isEmpty()) Text("Tap a notification or press its buttons", style = MaterialTheme.typography.bodySmall)
        events.take(15).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}
