package com.sparsh.dialer.ui.home

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Whatsapp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.sparsh.dialer.LocalActions
import com.sparsh.dialer.Screen
import com.sparsh.dialer.data.Contact
import com.sparsh.dialer.ui.theme.LocalExtra
import com.sparsh.dialer.util.Fmt

@Composable
internal fun ContactMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    number: String,
    name: String?,
    contact: Contact?,
    accountId: String? = null,
) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val key = Fmt.key(number)
    val normalized = contact?.numbers?.firstOrNull { it.key == key }?.normalizedNumber
    val canCall = key.isNotEmpty()

    DropdownMenu(expanded, onDismiss, shape = RoundedCornerShape(22.dp), containerColor = LocalExtra.current.card) {
        DropdownMenuItem(
            text = { Text("Copy number") },
            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
            enabled = canCall,
            onClick = {
                onDismiss()
                val international = Fmt.internationalNumber(context, number, normalized, accountId)
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Phone number", international ?: number))
            },
        )
        DropdownMenuItem(
            text = { Text("Share contact") },
            leadingIcon = { Icon(Icons.Rounded.Share, null) },
            enabled = canCall,
            onClick = {
                onDismiss()
                val international = Fmt.internationalNumber(context, number, normalized, accountId)
                shareContact(context, contact, name, international ?: number)
            },
        )
        DropdownMenuItem(
            text = { Text("Message on WhatsApp") },
            leadingIcon = { Icon(Icons.Rounded.Whatsapp, null) },
            enabled = canCall,
            onClick = {
                onDismiss()
                openWhatsApp(context, number, normalized, accountId)
            },
        )
        DropdownMenuItem(
            text = { Text("Open contact info") },
            leadingIcon = { Icon(Icons.Rounded.Info, null) },
            onClick = { onDismiss(); actions.open(Screen.Detail(number)) },
        )
    }
}

private fun shareContact(context: Context, contact: Contact?, name: String?, number: String) {
    val intent = Intent(Intent.ACTION_SEND)
    if (contact != null && contact.lookupKey.isNotBlank()) {
        val uri = ContactsContract.Contacts.CONTENT_VCARD_URI.buildUpon().appendPath(contact.lookupKey).build()
        intent.setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        intent.setType("text/plain").putExtra(Intent.EXTRA_TEXT, listOfNotNull(name, number).joinToString("\n"))
    }
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can share this contact", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Couldn't share this contact", Toast.LENGTH_SHORT).show()
    }
}

internal fun openWhatsApp(context: Context, number: String, normalized: String?, accountId: String?) {
    val international = Fmt.internationalNumber(context, number, normalized, accountId)
    if (international == null) {
        Toast.makeText(context, "WhatsApp needs a number with a country code", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = "https://wa.me/${international.removePrefix("+")}".toUri()
    for (app in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(app))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the other WhatsApp edition.
        }
    }
    Toast.makeText(context, "WhatsApp is unavailable", Toast.LENGTH_SHORT).show()
}
