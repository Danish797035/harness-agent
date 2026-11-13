package com.meenai.mina.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.ui.components.BarIconButton
import com.meenai.mina.ui.components.EmptyNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.SearchField
import com.meenai.mina.ui.components.SubPage
import com.meenai.mina.ui.components.TileTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.vault.PasswordVaultStore
import com.meenai.mina.vault.VaultEntry
import com.meenai.mina.vault.rememberAuthGate

@Composable
fun VaultScreen(
    vault: PasswordVaultStore,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val entries by vault.entries.collectAsState()
    val authGate = rememberAuthGate()
    val context = LocalContext.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var addingEntry by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<VaultEntry?>(null) }

    val displayedEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries else vault.search(searchQuery)
    }

    SubPage(
        title = stringResource(R.string.vault_title),
        onBack = onBack,
        right = {
            if (entries.isNotEmpty()) {
                BarIconButton(
                    icon = MinaIcons.Search,
                    description = stringResource(R.string.vault_search_hint),
                    onClick = {
                        searching = !searching
                        if (!searching) searchQuery = ""
                    },
                )
            }
            BarIconButton(
                icon = MinaIcons.Plus,
                description = stringResource(R.string.vault_add),
                onClick = { addingEntry = true },
            )
        },
    ) {
        if (searching || (searchQuery.isNotEmpty() && entries.isNotEmpty())) {
            SearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                hint = stringResource(R.string.vault_search_hint),
                modifier = Modifier.testTag("vault-search"),
            )
        }

        if (displayedEntries.isEmpty()) {
            GroupLabel("")
            EmptyNote(
                title = if (searchQuery.isBlank()) stringResource(R.string.vault_empty) else stringResource(R.string.not_found),
                detail = if (searchQuery.isBlank()) stringResource(R.string.vault_empty_sub) else "",
            )
            GroupLabel("")
            GroupCard {
                ListRow(
                    icon = MinaIcons.Plus,
                    title = stringResource(R.string.vault_add),
                    first = true,
                    titleColor = MinaTheme.colors.accent,
                    tone = TileTone.Accent,
                    onClick = { addingEntry = true },
                )
            }
        } else {
            GroupLabel("")
            GroupCard {
                displayedEntries.forEachIndexed { i, entry ->
                    val subtitle = if (entry.url.isNullOrBlank()) {
                        entry.username
                    } else {
                        "${entry.username} · ${entry.url}"
                    }
                    ListRow(
                        icon = MinaIcons.Lock,
                        title = entry.title,
                        subtitle = subtitle,
                        first = i == 0,
                        onClick = { selectedEntry = entry },
                    ) {
                        RowChevron()
                    }
                }
            }
        }
    }

    // Add entry dialog
    if (addingEntry) {
        AddVaultEntryDialog(
            onDismiss = { addingEntry = false },
            onSave = { entry, password ->
                vault.saveEntry(entry, password)
                addingEntry = false
            },
        )
    }

    // Entry detail dialog with biometric/device unlock gate
    selectedEntry?.let { entry ->
        VaultEntryDetailDialog(
            entry = entry,
            onDismiss = { selectedEntry = null },
            onDelete = {
                selectedEntry = null
                deletingEntry = entry
            },
            onRetrievePassword = { onPasswordReady ->
                authGate.authenticate(
                    title = context.getString(R.string.vault_auth_prompt),
                    subtitle = context.getString(R.string.vault_auth_desc),
                ) {
                    val pass = vault.getPassword(entry.id)
                    if (pass != null) onPasswordReady(pass)
                }
            },
            onCopyPassword = {
                authGate.authenticate(
                    title = context.getString(R.string.vault_auth_prompt),
                    subtitle = context.getString(R.string.vault_auth_desc),
                ) {
                    val pass = vault.getPassword(entry.id)
                    if (pass != null) {
                        copySecretToClipboard(context, pass)
                        Toast.makeText(context, context.getString(R.string.vault_copied), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCopyUsername = {
                copyTextToClipboard(context, entry.username)
                Toast.makeText(context, context.getString(R.string.vault_copied), Toast.LENGTH_SHORT).show()
            },
        )
    }

    // Delete confirmation dialog
    deletingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletingEntry = null },
            title = { Text(stringResource(R.string.vault_delete)) },
            text = { Text(stringResource(R.string.vault_delete_confirm, entry.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vault.deleteEntry(entry.id)
                        deletingEntry = null
                    },
                ) {
                    Text(stringResource(R.string.vault_delete), color = MinaTheme.colors.warn)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingEntry = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AddVaultEntryDialog(
    onDismiss: () -> Unit,
    onSave: (VaultEntry, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val canSave = title.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.vault_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("vault-input-title"),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.vault_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("vault-input-username"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.vault_password_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) MinaIcons.EyeOff else MinaIcons.Eye,
                                contentDescription = null,
                                tint = MinaTheme.colors.icon,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("vault-input-password"),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.vault_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("vault-input-url"),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.vault_notes_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("vault-input-notes"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        onSave(
                            VaultEntry(title = title, username = username, url = url.ifBlank { null }, notes = notes.ifBlank { null }),
                            password,
                        )
                    }
                },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.vault_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun VaultEntryDetailDialog(
    entry: VaultEntry,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRetrievePassword: ((String) -> Unit) -> Unit,
    onCopyPassword: () -> Unit,
    onCopyUsername: () -> Unit,
) {
    var revealedPassword by remember { mutableStateOf<String?>(null) }
    var isRevealed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.title, style = MinaType.Title)
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MinaIcons.Trash,
                        contentDescription = stringResource(R.string.vault_delete),
                        tint = MinaTheme.colors.warn,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Username field with copy
                Column {
                    Text(
                        text = stringResource(R.string.vault_username_label),
                        style = MinaType.Caption,
                        color = MinaTheme.colors.subtext,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.username, style = MinaType.Body)
                        IconButton(onClick = onCopyUsername) {
                            Icon(
                                imageVector = MinaIcons.Copy,
                                contentDescription = stringResource(R.string.vault_copy_username),
                                tint = MinaTheme.colors.icon,
                            )
                        }
                    }
                }

                // Password field with biometric gate view & copy
                Column {
                    Text(
                        text = stringResource(R.string.vault_password_label),
                        style = MinaType.Caption,
                        color = MinaTheme.colors.subtext,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val displayPass = if (isRevealed && revealedPassword != null) {
                            revealedPassword!!
                        } else {
                            "••••••••••••"
                        }
                        Text(
                            text = displayPass,
                            style = MinaType.Body.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Row {
                            IconButton(
                                onClick = {
                                    if (isRevealed) {
                                        isRevealed = false
                                        revealedPassword = null
                                    } else {
                                        onRetrievePassword { pass ->
                                            revealedPassword = pass
                                            isRevealed = true
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (isRevealed) MinaIcons.EyeOff else MinaIcons.Eye,
                                    contentDescription = stringResource(if (isRevealed) R.string.vault_hide_password else R.string.vault_view_password),
                                    tint = MinaTheme.colors.icon,
                                )
                            }
                            IconButton(onClick = onCopyPassword) {
                                Icon(
                                    imageVector = MinaIcons.Copy,
                                    contentDescription = stringResource(R.string.vault_copy_password),
                                    tint = MinaTheme.colors.icon,
                                )
                            }
                        }
                    }
                }

                if (!entry.url.isNullOrBlank()) {
                    Column {
                        Text(
                            text = stringResource(R.string.vault_url_label),
                            style = MinaType.Caption,
                            color = MinaTheme.colors.subtext,
                        )
                        Text(entry.url, style = MinaType.Body)
                    }
                }

                if (!entry.notes.isNullOrBlank()) {
                    Column {
                        Text(
                            text = stringResource(R.string.vault_notes_label),
                            style = MinaType.Caption,
                            color = MinaTheme.colors.subtext,
                        )
                        Text(entry.notes, style = MinaType.Body)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.vault_close))
            }
        },
    )
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
}

/**
 * Copies password to clipboard and marks it as sensitive on Android 13+ to suppress preview overlays.
 */
private fun copySecretToClipboard(context: Context, secret: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("password", secret).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
    }
    clipboard.setPrimaryClip(clip)
}
