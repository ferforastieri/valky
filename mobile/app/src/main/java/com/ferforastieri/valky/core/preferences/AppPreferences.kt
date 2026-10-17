package com.ferforastieri.valky.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.valkyPreferences by preferencesDataStore("valky_preferences")

@Singleton class AppPreferences @Inject constructor(@param:ApplicationContext private val context:Context){
    private val themeKey=stringPreferencesKey("theme")
    private val languageKey=stringPreferencesKey("language")
    val theme=context.valkyPreferences.data.map{it[themeKey]?:"system"}
    val language=context.valkyPreferences.data.map{it[languageKey]?:"system"}
    suspend fun setTheme(value:String)=context.valkyPreferences.edit{it[themeKey]=value}
    suspend fun setLanguage(value:String)=context.valkyPreferences.edit{it[languageKey]=value}
}
