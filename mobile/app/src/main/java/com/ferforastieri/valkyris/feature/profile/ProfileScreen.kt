package com.ferforastieri.valkyris.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ferforastieri.valkyris.core.design.ProfileAvatar
import com.ferforastieri.valkyris.core.design.encodeProfileAvatar
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(admin: Boolean, vm: ProfileViewModel = hiltViewModel()) {
    val current by vm.profile.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(current?.id, current?.name) { mutableStateOf(current?.name.orEmpty()) }
    var avatarData by remember(current?.id, current?.avatarData) { mutableStateOf(current?.avatarData.orEmpty()) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.Default) { encodeProfileAvatar(context, uri) }?.let { avatarData = it }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val profile = current
        if (profile == null) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileAvatar(avatarData, "Foto de perfil", Modifier.size(104.dp))
                    OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = !saving) { Text("Alterar foto") }
                    Text("Este perfil representa você e o celular que está usando.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.save(profile.copy(name = name.trim(), avatarData = avatarData)) },
                enabled = !saving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salvar perfil") }

            if (admin) {
            Text("Senha da casa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text("Essa é a senha usada para entrar nesta instalação.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(currentPassword, { currentPassword = it }, label = { Text("Senha atual") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newPassword, { newPassword = it }, label = { Text("Nova senha") }, supportingText = { Text("No mínimo 10 caracteres") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Confirmar nova senha") }, isError = confirmation.isNotEmpty() && confirmation != newPassword, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    vm.changePassword(currentPassword, newPassword)
                    currentPassword = ""
                    newPassword = ""
                    confirmation = ""
                },
                enabled = !saving && currentPassword.isNotBlank() && newPassword.length >= 10 && newPassword == confirmation,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Alterar senha") }
            }
        }
    }
}
