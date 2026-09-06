package com.ferforastieri.valkyris.feature.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ferforastieri.valkyris.MainViewModel
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.SignalLine
import com.ferforastieri.valkyris.core.design.ValkyrisMark
import com.composables.icons.lucide.LogIn
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanQrCode
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun OnboardingScreen(viewModel: MainViewModel) {
    var url by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val initialized by viewModel.authInitialized.collectAsStateWithLifecycle()
    val scanPrompt = stringResource(R.string.scan_invite_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.acceptPairingLink(Uri.parse(it), userName) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ValkyrisMark(Modifier.size(82.dp))
                        Text(
                            stringResource(R.string.login_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.login_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    SignalLine(Modifier.fillMaxWidth().height(52.dp))
                    OutlinedTextField(
                        url,
                        { url = it; viewModel.resetAuthStatus() },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.server_url)) },
                        placeholder = { Text("https://seu-dominio.exemplo") },
                        enabled = initialized == null && !connecting,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        userName,
                        { userName = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Seu nome") },
                        supportingText = { Text("Cada pessoa é vinculada ao próprio celular.") },
                        enabled = !connecting,
                        singleLine = true,
                    )
                    if (initialized != null) {
                        Text(
                            stringResource(if (initialized == false) R.string.create_admin_body else R.string.login_admin_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            password,
                            { password = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(stringResource(if (initialized == false) R.string.create_home_password else R.string.home_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        if (initialized == false) {
                            OutlinedTextField(
                                confirmation,
                                { confirmation = it },
                                Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.confirm_home_password)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                isError = confirmation.isNotEmpty() && confirmation != password,
                            )
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            if (initialized == null) viewModel.inspectServer(url)
                            else viewModel.login(url, password, bootstrap = initialized == false, userName = userName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting && url.isNotBlank() && (
                            initialized == null || userName.isNotBlank() && password.isNotBlank() && (initialized == true || password == confirmation)
                        ),
                    ) {
                        if (connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Lucide.LogIn, null)
                        Text(
                            stringResource(
                                when (initialized) {
                                    null -> R.string.continue_action
                                    false -> R.string.create_home
                                    true -> R.string.login
                                },
                            ),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                    if (initialized != null) {
                        TextButton(
                            onClick = { password = ""; confirmation = ""; viewModel.resetAuthStatus() },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(stringResource(R.string.change_server)) }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.invited_question), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = {
                            scanner.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt(scanPrompt)
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting && userName.isNotBlank(),
                    ) {
                        Icon(Lucide.ScanQrCode, null)
                        Text(stringResource(R.string.scan_invite), Modifier.padding(start = 8.dp))
                    }
                }
            }
            Text(
                "Valkyris · local-first",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
