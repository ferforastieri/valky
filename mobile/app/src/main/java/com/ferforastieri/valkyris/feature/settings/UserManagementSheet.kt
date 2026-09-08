package com.ferforastieri.valkyris.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.ManagedUser

@Composable
fun UserManagementSheet(users: List<ManagedUser>, error: String?, busy: Boolean, onSave: (ManagedUser)->Unit, onRemove: (ManagedUser)->Unit, onDismiss: ()->Unit) {
 var removing by remember { mutableStateOf<ManagedUser?>(null) }
 ValkyrisBottomSheet(title="Gerenciar usuários",onDismiss=onDismiss) {
 Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
 error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
 users.forEach { user -> key(user.id) {
 var name by remember(user) {mutableStateOf(user.name)}
 var enabled by remember(user) {mutableStateOf(user.enabled)}
 var admin by remember(user) {mutableStateOf(user.admin)}
 Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
 OutlinedTextField(name,{name=it},label={Text("Nome")},enabled=!busy,modifier=Modifier.fillMaxWidth())
 Row { Checkbox(enabled,{enabled=it},enabled=!busy);Text("Acesso ativo",Modifier.padding(top=12.dp)) }
 Row { Checkbox(admin,{admin=it},enabled=!busy);Text("Administrador",Modifier.padding(top=12.dp)) }
 Text("${user.devices} dispositivos",style=MaterialTheme.typography.bodySmall)
 Row {TextButton({onSave(user.copy(name=name,enabled=enabled,admin=admin))},enabled=!busy&&name.isNotBlank()){Text("Salvar")};TextButton({removing=user},enabled=!busy){Text("Remover",color=MaterialTheme.colorScheme.error)}}
 }}
 }}
 }
 }
 removing?.let { user -> AlertDialog(onDismissRequest={removing=null},title={Text("Remover ${user.name}?")},text={Text("O acesso dos dispositivos deste usuário será revogado.")},confirmButton={TextButton({onRemove(user);removing=null},enabled=!busy){Text("Remover")}},dismissButton={TextButton({removing=null}){Text("Cancelar")}}) }
}
