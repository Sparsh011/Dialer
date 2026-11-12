package com.sparsh.dialer.ui.settings

import android.content.Intent
import android.media.MediaPlayer
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sparsh.dialer.AppViewModel
import com.sparsh.dialer.LocalActions
import com.sparsh.dialer.Screen
import com.sparsh.dialer.data.Prefs
import com.sparsh.dialer.data.RecordingEntity
import com.sparsh.dialer.ui.components.*
import com.sparsh.dialer.ui.theme.AccentPresets
import com.sparsh.dialer.ui.theme.LocalExtra
import com.sparsh.dialer.ui.theme.accentHex
import com.sparsh.dialer.ui.theme.parseAccent
import com.sparsh.dialer.util.Fmt
import com.sparsh.dialer.util.rememberHaptics
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun SettingsScreen(vm: AppViewModel, isDefault: Boolean) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val haptics = rememberHaptics()
    val theme by Prefs.theme.collectAsStateWithLifecycle()
    val accent by Prefs.accent.collectAsStateWithLifecycle()
    val hapticsOn by Prefs.haptics.collectAsStateWithLifecycle()
    val tones by Prefs.tones.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    var editingAccent by rememberSaveable { mutableStateOf(false) }
    BigHeaderList(title = "Settings", state = rememberLazyListState(), onBack = actions.back) {
        item {
            GroupLabel("Appearance")
            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Segmented(listOf("System", "Light", "Dark"), theme) { haptics.select(); Prefs.setTheme(it) }
                    Spacer(Modifier.height(20.dp))
                    Text("Accent colour", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentPresets.forEach { (name, color) ->
                            FilterChip(
                                selected = accent == color,
                                onClick = { haptics.select(); Prefs.setAccent(color) },
                                label = { Text(name) },
                                leadingIcon = { AccentSwatch(color) },
                            )
                        }
                        val custom = accent !in AccentPresets.values
                        FilterChip(
                            selected = custom,
                            onClick = { editingAccent = true },
                            label = { Text(if (custom) "Custom ${accentHex(accent)}" else "Custom hex") },
                            leadingIcon = {
                                if (custom) AccentSwatch(accent)
                                else Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
        }
        item {
            GroupLabel("Feedback")
            SectionCard {
                ToggleRow(Icons.Rounded.Vibration, "Haptic feedback", "Vibrate on touches, call connect and call end", hapticsOn) {
                    Prefs.setHaptics(it); haptics.toggle(it)
                }
                ToggleRow(Icons.Rounded.MusicNote, "Keypad tones", "Play a tone for each key", tones) {
                    haptics.toggle(it); Prefs.setTones(it)
                }
            }
        }
        if (recordings.isNotEmpty()) item {
            GroupLabel("Saved recordings")
            SectionCard {
                LinkRow(Icons.Rounded.GraphicEq, "Recordings", "${recordings.size} saved") { actions.open(Screen.Recordings()) }
            }
        }
        item {
            GroupLabel("Phone")
            SectionCard {
                LinkRow(
                    Icons.Rounded.Phone, "Default phone app",
                    if (isDefault) "Dialer handles your calls" else "Tap to make Dialer the default",
                ) { if (!isDefault) actions.requestDefault() }
                LinkRow(Icons.Rounded.Block, "Blocked numbers", "Manage the system block list") {
                    runCatching {
                        context.startActivity(context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent())
                    }
                }
                LinkRow(Icons.Rounded.SimCard, "Calling accounts", "Default SIM, call forwarding, voicemail") {
                    runCatching { context.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
                }
            }
        }
        item {
            Text(
                "Dialer 1.0 · Your call history is stored only on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 22.dp),
            )
        }
    }

    if (editingAccent) {
        var input by rememberSaveable { mutableStateOf(accentHex(accent)) }
        val parsed = parseAccent(input)
        AlertDialog(
            onDismissRequest = { editingAccent = false },
            title = { Text("Custom accent colour") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hex colour") },
                    supportingText = { Text("Enter six hex digits, for example #42D6A4.") },
                    trailingIcon = { parsed?.let { AccentSwatch(it) } },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = parsed != null,
                    onClick = { parsed?.let(Prefs::setAccent); editingAccent = false },
                ) { Text("Set as default") }
            },
            dismissButton = { TextButton(onClick = { editingAccent = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccentSwatch(color: Int) {
    Box(Modifier.size(16.dp).background(Color(color), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(46.dp).background(LocalExtra.current.fill, CircleShape).border(1.dp, LocalExtra.current.hairline, CircleShape).padding(4.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, spring(0.62f, 400f), label = "seg")
        Box(Modifier.graphicsLayer { translationX = x.toPx() }.width(w).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface, CircleShape))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().pressable(CircleShape, 0.94f, haptic = false) { if (i != selected) onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (i == selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.padding(start = 4.dp, end = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(haptic = false, pressedScale = 0.99f) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked, null)
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.99f, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = LocalExtra.current.subtle)
    }
}

@Composable
fun RecordingsScreen(vm: AppViewModel, key: String?) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val haptics = rememberHaptics()
    val all by vm.recordings.collectAsStateWithLifecycle()
    val list = remember(all, key) { if (key == null) all else all.filter { it.numKey == key } }

    var playing by remember { mutableLongStateOf(-1L) }
    var progress by remember { mutableFloatStateOf(0f) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(playing) {
        while (playing != -1L) {
            runCatching { progress = player.currentPosition / player.duration.coerceAtLeast(1).toFloat() }
            delay(60)
        }
        progress = 0f
    }
    var deleting by remember { mutableStateOf<RecordingEntity?>(null) }

    fun toggle(r: RecordingEntity) {
        runCatching {
            if (playing == r.id) {
                player.stop(); playing = -1
            } else {
                player.reset()
                player.setDataSource(r.path)
                player.setOnCompletionListener { playing = -1 }
                player.prepare(); player.start()
                playing = r.id
            }
        }.onFailure {
            playing = -1
            Toast.makeText(context, "Couldn't play this recording", Toast.LENGTH_SHORT).show()
        }
    }

    BigHeaderList(
        title = "Recordings",
        subtitle = if (list.isEmpty()) null else "${list.size} saved",
        state = rememberLazyListState(), onBack = actions.back,
    ) {
        if (list.isEmpty()) {
            item { EmptyState(Icons.Rounded.GraphicEq, "No recordings", "Saved call recordings appear here.") }
        }
        items(list, key = { it.id }) { r ->
            val on = playing == r.id
            Row(
                Modifier
                    .animateItem()
                    .padding(horizontal = 16.dp, vertical = 5.dp)
                    .fillMaxWidth()
                    .background(LocalExtra.current.card, RoundedCornerShape(24.dp))
                    .border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(24.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (on) progress else 0f }, modifier = Modifier.size(52.dp), strokeWidth = 3.dp,
                        trackColor = Color.Transparent, color = MaterialTheme.colorScheme.primary,
                    )
                    CircleAction(
                        if (on) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, size = 44.dp,
                        container = LocalExtra.current.fill, content = MaterialTheme.colorScheme.onSurface, bordered = false,
                    ) { toggle(r) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name ?: Fmt.number(r.number), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Fmt.relative(r.startedAt)} · ${Fmt.clock(r.durationMs / 1000)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TopIcon(Icons.Outlined.IosShare, "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant) {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(r.path))
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                null,
                            ),
                        )
                    }
                }
                TopIcon(Icons.Outlined.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) { deleting = r }
            }
        }
    }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = LocalExtra.current.card,
            title = { Text("Delete recording?") },
            text = { Text("${r.name ?: Fmt.number(r.number)} · ${Fmt.relative(r.startedAt)}") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.reject()
                    if (playing == r.id) { runCatching { player.stop() }; playing = -1 }
                    vm.deleteRecording(r); deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
