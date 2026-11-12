package com.sparsh.dialer.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sparsh.dialer.AppViewModel
import com.sparsh.dialer.LocalActions
import com.sparsh.dialer.Screen
import com.sparsh.dialer.data.Blocking
import com.sparsh.dialer.data.CallEntity
import com.sparsh.dialer.data.CallType
import com.sparsh.dialer.data.ContactsRepo
import com.sparsh.dialer.data.PhoneEntry
import com.sparsh.dialer.data.Sims
import com.sparsh.dialer.ui.components.*
import com.sparsh.dialer.ui.home.FEATURE_WIFI
import com.sparsh.dialer.ui.home.PopIn
import com.sparsh.dialer.ui.theme.LocalExtra
import com.sparsh.dialer.util.Fmt
import com.sparsh.dialer.util.rememberHaptics

@Immutable
private sealed interface HistoryItem {
    @Immutable data class Day(val title: String, val day: Long) : HistoryItem
    @Immutable data class Entry(val call: CallEntity, val first: Boolean, val last: Boolean, val recorded: Boolean) : HistoryItem
}

/** Full call history for one person (all of their numbers) or one unknown number. */
@Composable
fun DetailScreen(number: String, vm: AppViewModel) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val haptics = rememberHaptics()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val sims by Sims.sims.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val state = rememberLazyListState()

    val key = remember(number) { Fmt.key(number) }
    val contact = contacts.byKey[key]
    val numbers: List<PhoneEntry> = contact?.numbers ?: remember(number) { listOf(PhoneEntry(number, key, "")) }
    val keys = remember(numbers) { numbers.map { it.key } }
    val history by remember(keys) { vm.history(keys) }.collectAsStateWithLifecycle(null)
    val name = contact?.name ?: history?.firstOrNull()?.cachedName
    val primary = numbers.firstOrNull { it.key == key } ?: numbers.first()

    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<List<Long>?>(null) }
    var blocked by remember(number) { mutableStateOf(Blocking.isBlocked(context, number)) }

    val items = remember(history, recordings) {
        val list = history.orEmpty()
        val recs = recordings.filter { it.numKey in keys }
        val out = ArrayList<HistoryItem>(list.size + 16)
        var day = Long.MAX_VALUE
        val now = System.currentTimeMillis()
        for ((i, c) in list.withIndex()) {
            val first = c.date < day
            if (first) { day = Fmt.startOfDay(c.date); out.add(HistoryItem.Day(Fmt.dayTitle(day, now), day)) }
            val last = i == list.lastIndex || list[i + 1].date < day
            val recorded = recs.any { it.startedAt >= c.date - 5_000 && it.startedAt <= c.date + c.duration * 1000 + 90_000 }
            out.add(HistoryItem.Entry(c, first, last, recorded))
        }
        out
    }
    val stats = remember(history) {
        val list = history.orEmpty()
        Triple(list.size, list.sumOf { it.duration }, list.count { it.type == CallType.MISSED })
    }

    BigHeaderList(
        title = name ?: Fmt.number(number),
        state = state,
        onBack = actions.back,
        headerFraction = 0.4f,
        actions = {
            if (contact != null) {
                TopIcon(
                    if (contact.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite",
                    tint = if (contact.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                ) { haptics.toggle(!contact.starred); ContactsRepo.setStarred(context, contact.id, !contact.starred) }
            }
            Box {
                TopIcon(Icons.Rounded.MoreVert, "More") { menu = true }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(22.dp), containerColor = LocalExtra.current.card) {
                    if (contact != null) {
                        DropdownMenuItem({ Text("Edit contact") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                            menu = false
                            runCatching {
                                val uri = ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey)
                                context.startActivity(Intent(Intent.ACTION_EDIT).setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE))
                            }
                        })
                    } else if (key.isNotEmpty()) {
                        DropdownMenuItem({ Text("Add to contacts") }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) }, onClick = {
                            menu = false
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                        .putExtra(ContactsContract.Intents.Insert.PHONE, number),
                                )
                            }
                        })
                    }
                    if (key.isNotEmpty()) {
                        DropdownMenuItem({ Text("Copy number") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
                            menu = false
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Phone number", Fmt.internationalNumber(context, primary.number, primary.normalizedNumber) ?: primary.number))
                        })
                        DropdownMenuItem({ Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                            menu = false
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, listOfNotNull(name, primary.number).joinToString("\n")),
                                        null,
                                    ),
                                )
                            }
                        })
                        DropdownMenuItem(
                            { Text(if (blocked) "Unblock number" else "Block number") },
                            leadingIcon = { Icon(Icons.Rounded.Block, null) },
                            onClick = {
                                menu = false
                                if (Blocking.setBlocked(context, primary.number, !blocked)) {
                                    blocked = !blocked
                                    haptics.confirm()
                                } else {
                                    Toast.makeText(context, "Set Dialer as the default phone app to block numbers", Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                    if (!history.isNullOrEmpty()) {
                        DropdownMenuItem(
                            { Text("Delete all history", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; deleting = history.orEmpty().map { it.id } },
                        )
                    }
                }
            }
        },
        header = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                Avatar(name, key.ifEmpty { "?" }, contact?.photoUri, 96.dp)
                Spacer(Modifier.height(18.dp))
                Text(
                    name ?: Fmt.number(number), style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (name != null) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        Fmt.number(primary.number) + (primary.label.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (blocked) {
                    Spacer(Modifier.height(6.dp))
                    Text("Blocked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        item(key = "__actions", contentType = "actions") {
            val extra = LocalExtra.current
            val canCall = key.isNotEmpty()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                PopIn(0, Modifier.weight(1f)) {
                    PillButton(
                        Icons.Rounded.Call, "Call", Modifier.fillMaxWidth(), height = 58.dp,
                        container = if (canCall) extra.success else extra.fill,
                        content = if (canCall) extra.onSuccess else extra.subtle, filled = canCall,
                        onLongClick = { if (canCall) actions.call(primary.number, name, true) },
                    ) { if (canCall) actions.call(primary.number, name, false) }
                }
                PopIn(1, Modifier.weight(1f)) {
                    PillButton(Icons.Outlined.ChatBubbleOutline, "Message", Modifier.fillMaxWidth(), height = 58.dp) {
                        if (canCall) actions.message(primary.number)
                    }
                }
                if (recordings.any { it.numKey == key }) {
                    PopIn(2) {
                        CircleAction(Icons.Rounded.GraphicEq, null, size = 58.dp) { actions.open(Screen.Recordings(key)) }
                    }
                }
            }
        }
        item(key = "__stats", contentType = "stats") {
            val extra = LocalExtra.current
            SectionCard(Modifier.padding(top = 12.dp)) {
                Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 16.dp)) {
                    Stat("${stats.first}", "Calls", Modifier.weight(1f))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(extra.hairline))
                    Stat(Fmt.duration(stats.second).ifEmpty { "0s" }, "Talk time", Modifier.weight(1f))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(extra.hairline))
                    Stat("${stats.third}", "Missed", Modifier.weight(1f), if (stats.third > 0) extra.missed else null)
                }
            }
        }
        if (numbers.size > 1) {
            item(key = "__numbers", contentType = "numbers") {
                SectionCard(Modifier.padding(top = 12.dp)) {
                    numbers.forEach { n ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.number(n.number), style = MaterialTheme.typography.titleMedium)
                                Text(n.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TopIcon(Icons.Outlined.ChatBubbleOutline, "Message") { actions.message(n.number) }
                            CircleAction(
                                Icons.Rounded.Call, null, size = 44.dp,
                                container = LocalExtra.current.successContainer, content = LocalExtra.current.success, bordered = false,
                                onLongClick = { actions.call(n.number, name, true) },
                            ) { actions.call(n.number, name, false) }
                        }
                    }
                }
            }
        }
        if (history != null && items.isEmpty()) {
            item(key = "__empty") { EmptyState(Icons.Rounded.History, "No call history", "Calls with this number will be listed here.") }
        }
        items(
            items,
            key = { if (it is HistoryItem.Entry) it.call.id else "d" + (it as HistoryItem.Day).day },
            contentType = { if (it is HistoryItem.Entry) 1 else 0 },
        ) { item ->
            when (item) {
                is HistoryItem.Day -> GroupLabel(item.title, Modifier.animateItem())
                is HistoryItem.Entry -> HistoryRow(item, sims.size > 1, numbers.size > 1, Modifier.animateItem()) { deleting = listOf(item.call.id) }
            }
        }
    }

    deleting?.let { ids ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = LocalExtra.current.card,
            title = { Text(if (ids.size > 1) "Delete all ${ids.size} calls?" else "Delete this call?") },
            text = { Text("This also removes it from the phone's call log and can't be undone.") },
            confirmButton = {
                TextButton(onClick = { haptics.reject(); vm.delete(ids); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, tint: androidx.compose.ui.graphics.Color? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.subtle)
    }
}

@Composable
private fun HistoryRow(item: HistoryItem.Entry, twoSims: Boolean, showNumber: Boolean, modifier: Modifier, onLong: () -> Unit) {
    val c = item.call
    val extra = LocalExtra.current
    val tint = typeColor(c.type)
    Row(
        modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .pressable(RoundedCornerShape(20.dp), 0.985f, onLongClick = onLong) {}
            .rowDivider(extra.hairline, !item.last, inset = 66.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(tint.copy(alpha = if (extra.isDark) 0.14f else 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(typeIcon(c.type), null, Modifier.size(17.dp), tint = tint) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                CallType.label(c.type), style = MaterialTheme.typography.titleMedium, fontSize = 15.5.sp,
                color = if (c.type == CallType.MISSED) extra.missed else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val d = Fmt.duration(c.duration)
                Text(
                    Fmt.time(c.date) + (if (d.isNotEmpty() && c.type != CallType.MISSED) "  ·  $d" else "") +
                        (if (showNumber) "  ·  ${Fmt.number(c.number)}" else ""),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.recorded) Icon(Icons.Rounded.GraphicEq, "Recorded", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            if (c.features and FEATURE_WIFI != 0) Icon(Icons.Rounded.Wifi, "Wi-Fi call", Modifier.size(15.dp), tint = extra.subtle)
            if (twoSims) SimBadge(Sims.slotFor(c.accountId))
        }
    }
}
