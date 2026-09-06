package com.ferforastieri.valkyris.core.network

import com.ferforastieri.valkyris.core.security.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun api(session: SessionStore) = ValkyrisApi { session.get() }
}
