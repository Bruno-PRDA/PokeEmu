package com.pokeemu.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Searchable list of (id, name). When [names] is empty (table not found in
 * the ROM) it falls back to typing a number.
 */
@Composable
fun IdPicker(title: String, names: List<String>, allowZero: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val entries = remember(names, allowZero) {
        val none = if (allowZero) listOf(0 to "(none)") else emptyList()
        // Skip index 0 and placeholder entries like "??????????" / "-------".
        none + names.mapIndexedNotNull { id, name -> if (id == 0 || name.isBlank() || name.all { it == '?' || it == '-' }) null else id to name }
    }
    val filtered = remember(query, entries) {
        val q = query.trim()
        if (q.isEmpty()) entries else entries.filter { (id, name) -> name.contains(q, ignoreCase = true) || id.toString() == q }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it },
                    label = { Text(if (names.isEmpty()) "Number" else "Search or number") },
                    singleLine = true,
                    keyboardOptions = if (names.isEmpty()) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (names.isEmpty()) {
                    Text("This ROM's name table wasn't found, so enter the number.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.first }) { (id, name) ->
                            Text(
                                "$name  ·  #$id",
                                Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (names.isEmpty()) TextButton(onClick = { query.trim().toIntOrNull()?.let(onPick) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
