package com.ferforastieri.valky.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.valkyrisPreferences by preferencesDataStore("valkyris_preferences")

@Singleton class AppPreferences @Inject constructor(@param:ApplicationContext private val context:Context){
    private val themeKey=stringPreferencesKey("theme")
    private val languageKey=stringPreferencesKey("language")
    val theme=context.valkyrisPreferences.data.map{it[themeKey]?:"system"}
    val language=context.valkyrisPreferences.data.map{it[languageKey]?:"system"}
    suspend fun setTheme(value:String)=context.valkyrisPreferences.edit{it[themeKey]=value}
    suspend fun setLanguage(value:String)=context.valkyrisPreferences.edit{it[languageKey]=value}
}
