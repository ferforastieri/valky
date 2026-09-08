package com.ferforastieri.valkyris.feature.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ferforastieri.valkyris.MainViewModel
import com.ferforastieri.valkyris.core.design.ValkyrisMark
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun OnboardingScreen(viewModel: MainViewModel) {
    var url by remember { mutableStateOf(viewModel.previousServer) }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val initialized by viewModel.authInitialized.collectAsStateWithLifecycle()
    val invitation by viewModel.pairingLink.collectAsStateWithLifecycle()
    val firstAccess = invitation != null || initialized == false
    LaunchedEffect(invitation) { invitation?.let { url = it.getQueryParameter("url").orEmpty(); password = ""; confirmation = "" } }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.acceptPairingLink(Uri.parse(it)) }
    }
    AccountSurface {
        ValkyrisMark(Modifier.size(72.dp).align(Alignment.CenterHorizontally))
        Text(if (firstAccess) "Crie sua conta" else "Entre na sua conta", style = MaterialTheme.typography.headlineMedium)
        Text(if (firstAccess) "O convite é usado apenas neste cadastro. Depois, entre com seu usuário e senha." else "Use a mesma conta para manter seu perfil e histórico, mesmo em outro aparelho.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(url, { url = it; viewModel.resetAuthStatus() }, label = { Text("Endereço do servidor") }, placeholder = { Text("https://seu-dominio.exemplo") }, singleLine = true, enabled = !connecting && initialized == null && invitation == null, modifier = Modifier.fillMaxWidth())
        if (initialized != null) {
            if (firstAccess) OutlinedTextField(name, { name = it }, label = { Text("Seu nome") }, singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth())
            UsernameField(username, { username = it }, !connecting)
            PasswordField(password, { password = it }, "Senha", !connecting)
            if (firstAccess) {
                Text("Use uma frase com pelo menos 12 caracteres.", style = MaterialTheme.typography.bodySmall)
                PasswordField(confirmation, { confirmation = it }, "Confirmar senha", !connecting)
                if (confirmation.isNotEmpty() && confirmation != password) Text("As senhas precisam ser iguais.", color = MaterialTheme.colorScheme.error)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            when {
                invitation != null -> viewModel.registerAccount(username, password, name)
                initialized == null -> viewModel.inspectServer(url)
                else -> viewModel.login(url, password, initialized == false, name, username)
            }
        }, enabled = !connecting && url.isNotBlank() && (initialized == null || username.isNotBlank() && password.isNotEmpty() && (!firstAccess || name.isNotBlank() && password == confirmation && password.toByteArray().size in 12..72)), modifier = Modifier.fillMaxWidth()) {
            if (connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (initialized == null) "Continuar" else if (firstAccess) "Criar conta" else "Entrar")
        }
        if (initialized != null) TextButton(onClick = { viewModel.cancelInvitation(); password = ""; confirmation = "" }, enabled = !connecting, modifier = Modifier.fillMaxWidth()) { Text(if (invitation != null) "Já tenho uma conta" else "Alterar servidor") }
        if (invitation == null) {
            HorizontalDivider()
            Text("Primeiro acesso?", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Escaneie o convite do administrador").setBeepEnabled(false).setOrientationLocked(false)) }, enabled = !connecting, modifier = Modifier.fillMaxWidth()) { Text("Escanear convite para criar conta") }
        }
    }
}

@Composable
fun CredentialsSetupScreen(viewModel: MainViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loaded by viewModel.permissionsLoaded.collectAsStateWithLifecycle()
    AccountSurface {
        Text(if (loaded) "Proteja sua conta" else "Verificando sua conta", style = MaterialTheme.typography.headlineMedium)
        if (loaded) {
            Text("Defina seu usuário e sua senha para os próximos acessos. Seu perfil e seu histórico serão mantidos.")
            UsernameField(username, { username = it }, !connecting)
            PasswordField(password, { password = it }, "Nova senha", !connecting)
            Text("Use uma frase com pelo menos 12 caracteres.", style = MaterialTheme.typography.bodySmall)
            PasswordField(confirmation, { confirmation = it }, "Confirmar senha", !connecting)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { viewModel.configureCredentials(username, password) }, enabled = !connecting && username.isNotBlank() && password.toByteArray().size in 12..72 && password == confirmation, modifier = Modifier.fillMaxWidth()) { Text(if (connecting) "Salvando…" else "Salvar e continuar") }
        } else {
            CircularProgressIndicator()
            TextButton(onClick = { viewModel.refreshPushRegistration() }) { Text("Tentar novamente") }
        }
        TextButton(onClick = viewModel::signOut, enabled = !connecting) { Text("Sair") }
    }
}

@Composable
private fun AccountSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun UsernameField(value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text("Usuário") }, supportingText = { Text("De 3 a 40 letras sem acento, números, ponto, hífen ou sublinhado.") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text(label) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
}
