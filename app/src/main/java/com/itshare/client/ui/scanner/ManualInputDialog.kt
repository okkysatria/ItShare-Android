package com.itshare.client.ui.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itshare.client.core.model.SmbPayload
import com.itshare.client.ui.uiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualInputDialog(
    language: String = "en",
    initialPayload: SmbPayload? = null,
    onDismiss: () -> Unit,
    onConnect: (SmbPayload) -> Unit
) {
    var host by remember(initialPayload) { mutableStateOf(initialPayload?.host.orEmpty()) }
    var port by remember(initialPayload) { mutableStateOf(initialPayload?.port?.toString() ?: "445") }
    var share by remember(initialPayload) { mutableStateOf(initialPayload?.share.orEmpty()) }
    var username by remember(initialPayload) { mutableStateOf(initialPayload?.user.orEmpty()) }
    var password by remember(initialPayload) { mutableStateOf(initialPayload?.pass.orEmpty()) }
    var isReadOnly by remember(initialPayload) { mutableStateOf(initialPayload?.readOnly ?: false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(uiText(language, "Manual connection", "Koneksi manual"), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText(language, "Back", "Kembali"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(uiText(language, "PC details", "Detail PC"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = { host = it; errorMessage = null },
                                    label = { Text(uiText(language, "Host / IP address", "Alamat host / IP")) },
                                    placeholder = { Text("192.168.1.24") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                                )
                                OutlinedTextField(
                                    value = port,
                                    onValueChange = { port = it; errorMessage = null },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    modifier = Modifier.width(92.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            OutlinedTextField(
                                value = share,
                                onValueChange = { share = it; errorMessage = null },
                                    label = { Text(uiText(language, "Share name", "Nama share")) },
                                    placeholder = { Text(uiText(language, "e.g. Documents", "contoh: Dokumen")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it; errorMessage = null },
                                label = { Text("Username") },
                                placeholder = { Text(uiText(language, "Windows username", "Nama pengguna Windows")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it; errorMessage = null },
                                label = { Text("Password") },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (passwordVisible) uiText(language, "Hide password", "Sembunyikan password") else uiText(language, "Show password", "Tampilkan password"))
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isReadOnly, onCheckedChange = { isReadOnly = it })
                                Column {
                                    Text(uiText(language, "Read-only", "Hanya baca"), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (errorMessage != null) {
                        Text(errorMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                    Button(
                        onClick = {
                            val portNumber = port.toIntOrNull()
                            when {
                                host.isBlank() -> errorMessage = uiText(language, "Host / IP is required", "Alamat host / IP wajib diisi")
                                portNumber == null || portNumber !in 1..65535 -> errorMessage = uiText(language, "Port must be between 1 and 65535", "Port harus antara 1 dan 65535")
                                share.isBlank() -> errorMessage = uiText(language, "Share name is required", "Nama share wajib diisi")
                                username.isBlank() -> errorMessage = uiText(language, "Username is required", "Username wajib diisi")
                                else -> onConnect(SmbPayload(version = 1, host = host.trim(), port = portNumber, share = share.trim(), user = username.trim(), pass = password, readOnly = isReadOnly, expEpoch = null))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(uiText(language, "Connect", "Sambungkan"), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
