package com.ferforastieri.valkyris.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.ManagedUser

@Composable
fun UserManagementSheet(users: List<ManagedUser>, error: String?, busy: Boolean, onSave: (ManagedUser)->Unit, onRemove: (ManagedUser)->Unit, onDismiss: ()->Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ManagedUser?>(null) }
    ValkyrisBottomSheet(title = "Gerenciar usuários", onDismiss = onDismiss, dismissEnabled = !busy) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Escolha uma pessoa para editar o perfil e as permissões de acesso.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            users.forEach { user -> key(user.id) {
                var name by remember(user) { mutableStateOf(user.name) }
                var enabled by remember(user) { mutableStateOf(user.enabled) }
                var admin by remember(user) { mutableStateOf(user.admin) }
                val expanded = editing == user.id
                OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Text(user.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(user.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${if (user.admin) "Administrador" else "Membro"} · ${if (user.enabled) "Ativo" else "Desativado"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${user.devices} dispositivo(s)", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton({ editing = if (expanded) null else user.id }, enabled = !busy) { Text(if (expanded) "Fechar" else "Editar") }
                        }
                        if (expanded) {
                            HorizontalDivider()
                            OutlinedTextField(name, { name = it }, label = { Text("Nome da pessoa") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            UserAccessOption("Acesso ao sistema", "Ao desativar, os dispositivos desta pessoa perdem o acesso.", enabled, !busy) { enabled = it }
                            UserAccessOption("Administrador", "Pode gerenciar usuários e configurações do sistema.", admin, !busy) { admin = it }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ removing = user }, enabled = !busy) { Text("Remover pessoa", color = MaterialTheme.colorScheme.error) }
                                Button({ onSave(user.copy(name = name.trim(), enabled = enabled, admin = admin)) }, enabled = !busy && name.isNotBlank()) {
                                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Salvar alterações")
                                }
                            }
                        }
                    }
                }
            } }
            Spacer(Modifier.height(8.dp))
        }
    }
    removing?.let { user -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remover ${user.name}?") }, text = { Text("O perfil será removido e os dispositivos desta pessoa perderão o acesso ao sistema.") }, confirmButton = { TextButton({ onRemove(user); removing = null }, enabled = !busy) { Text("Remover", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ removing = null }) { Text("Cancelar") } }) }
}

@Composable
private fun UserAccessOption(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
