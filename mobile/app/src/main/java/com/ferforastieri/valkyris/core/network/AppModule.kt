package com.ferforastieri.valkyris.core.network

import android.content.Context
import com.ferforastieri.valkyris.core.security.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun api(session: SessionStore, @ApplicationContext context: Context) =
        ValkyrisApi({ session.get() }) { context.getString(it) }
}
