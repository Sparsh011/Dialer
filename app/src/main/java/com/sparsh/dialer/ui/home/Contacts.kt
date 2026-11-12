package com.sparsh.dialer.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sparsh.dialer.AppViewModel
import com.sparsh.dialer.LocalActions
import com.sparsh.dialer.Screen
import com.sparsh.dialer.data.Contact
import com.sparsh.dialer.ui.components.*
import com.sparsh.dialer.ui.theme.LocalExtra

@Immutable
private sealed interface ContactItem {
    @Immutable data class Letter(val letter: String) : ContactItem
    @Immutable data class Row(val contact: Contact, val first: Boolean, val last: Boolean) : ContactItem
}

@Composable
fun ContactsScreen(vm: AppViewModel, state: LazyListState) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }

    val favorites = remember(contacts) { contacts.list.filter { it.starred } }
    val items = remember(contacts, query) {
        val q = query.trim()
        val digits = q.filter { it.isDigit() }
        val source = if (q.isEmpty()) contacts.list else contacts.list.filter { c ->
            c.name.contains(q, ignoreCase = true) || (digits.length >= 2 && c.numbers.any { it.key.contains(digits) || it.number.contains(digits) })
        }
        val out = ArrayList<ContactItem>(source.size + 30)
        var letter = ""
        for ((i, c) in source.withIndex()) {
            val first = c.letter != letter
            if (first) { letter = c.letter; out.add(ContactItem.Letter(letter)) }
            val last = i == source.lastIndex || source[i + 1].letter != letter
            out.add(ContactItem.Row(c, first, last))
        }
        out
    }

    BigHeaderList(
        title = "Contacts",
        subtitle = if (contacts.loaded) (if (contacts.list.size == 1) "1 person" else "${contacts.list.size} people") else null,
        state = state,
        bottomPadding = BottomBarSpace,
        actions = {
            TopIcon(Icons.Rounded.PersonAddAlt, "New contact") {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI))
                }
            }
        },
    ) {
        item(key = "__search", contentType = "search") {
            SearchField(query, "Search contacts", onFocus = LocalCollapseHeader.current) { query = it }
        }
        if (query.isBlank() && favorites.isNotEmpty()) {
            item(key = "__fav", contentType = "fav") {
                Column {
                    GroupLabel("Favourites")
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(favorites, key = { it.id }) { c ->
                            ContactMenuAnchor(c) { openMenu ->
                                Column(
                                    Modifier
                                        .width(80.dp)
                                        .pressable(RoundedCornerShape(22.dp), 0.92f, onLongClick = openMenu) {
                                            actions.open(Screen.Detail(c.numbers.first().number))
                                        }
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Avatar(c.name, c.numbers.first().key, c.photoUri, 58.dp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        c.name.substringBefore(' '), style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (contacts.loaded && items.isEmpty()) {
            item(key = "__empty") {
                EmptyState(
                    Icons.Rounded.Person, if (query.isBlank()) "No contacts" else "No matches",
                    if (query.isBlank()) "Contacts with phone numbers will appear here." else "Try a different name or number.",
                )
            }
        }
        items(
            items,
            key = { if (it is ContactItem.Row) it.contact.id else "l" + (it as ContactItem.Letter).letter },
            contentType = { if (it is ContactItem.Row) 1 else 0 },
        ) { item ->
            when (item) {
                is ContactItem.Letter -> GroupLabel(item.letter)
                is ContactItem.Row -> {
                    val c = item.contact
                    val extra = LocalExtra.current
                    ContactMenuAnchor(c) { openMenu ->
                        Row(
                            Modifier
                                .padding(horizontal = 8.dp)
                                .fillMaxWidth()
                                .pressable(RoundedCornerShape(20.dp), 0.985f, onLongClick = openMenu) {
                                    actions.open(Screen.Detail(c.numbers.first().number))
                                }
                                .rowDivider(extra.hairline, !item.last, inset = 72.dp)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(c.name, c.numbers.first().key, c.photoUri, 44.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (c.starred) Icon(Icons.Rounded.Star, "Favourite", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactMenuAnchor(contact: Contact, content: @Composable (openMenu: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        content { expanded = true }
        ContactMenu(expanded, { expanded = false }, contact.numbers.first().number, contact.name, contact)
    }
}
