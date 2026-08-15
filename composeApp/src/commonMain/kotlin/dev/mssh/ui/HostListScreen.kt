package dev.mssh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mssh.data.Host

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    hosts: List<Host>,
    onAdd: () -> Unit,
    onEdit: (Host) -> Unit,
    onConnect: (Host) -> Unit,
    onDelete: (Host) -> Unit,
) {
    val s = LocalAppStrings.current
    var query by remember { mutableStateOf("") }
    val filtered = hosts
        .filter { h ->
            query.isBlank() ||
                h.name.contains(query, ignoreCase = true) ||
                h.hostname.contains(query, ignoreCase = true) ||
                h.username.contains(query, ignoreCase = true) ||
                h.tags.any { it.contains(query, ignoreCase = true) }
        }
        .sortedWith(compareByDescending<Host> { it.lastConnectedAt }.thenBy { it.name.lowercase() })

    Scaffold(
        topBar = {
            MsshLargeHeader(title = "MSSH")
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Default.Add, s.hostsAdd) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                placeholder = { Text(s.hostsSearch) },
                singleLine = true,
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (hosts.isEmpty()) s.hostsEmpty else s.hostsNoMatch,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { host ->
                        HostCard(host, onConnect = { onConnect(host) }, onEdit = { onEdit(host) }, onDelete = { onDelete(host) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HostCard(host: Host, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val s = LocalAppStrings.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onConnect() },
    ) {
        ListItem(
            headlineContent = { Text(host.name) },
            supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${host.username}@${host.hostname}:${host.port}")
                    if (host.tags.isNotEmpty()) {
                        Text(host.tags.joinToString(" "), style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            leadingContent = {
                Text(host.name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall)
            },
            trailingContent = {
                Row {
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, s.hostsEdit) }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, s.hostsDelete) }
                }
            },
        )
    }
}
