package com.ferforastieri.valky.core.preferences

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Um arquivo corrompido não pode derrubar a abertura do app: o handler substitui o
// arquivo inválido por preferências vazias e o tema/idioma voltam ao padrão.
private val Context.valkyPreferences by preferencesDataStore(
    name = "valky_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton class AppPreferences @Inject constructor(@param:ApplicationContext private val context:Context){
    private val themeKey=stringPreferencesKey("theme")
    private val languageKey=stringPreferencesKey("language")
    // O MainViewModel coleta estes fluxos com SharingStarted.Eagerly: um erro de I/O
    // no DataStore subiria pelo viewModelScope e fecharia o app. catch mantém o fluxo
    // vivo caindo no padrão do sistema.
    val theme=context.valkyPreferences.data.map{it[themeKey]?:"system"}.catch{emit("system")}
    val language=context.valkyPreferences.data.map{it[languageKey]?:"system"}.catch{emit("system")}
    suspend fun setTheme(value:String)=context.valkyPreferences.edit{it[themeKey]=value}
    suspend fun setLanguage(value:String)=context.valkyPreferences.edit{it[languageKey]=value}
}
