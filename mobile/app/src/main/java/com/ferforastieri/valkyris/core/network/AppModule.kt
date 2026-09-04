package com.ferforastieri.valkyris.core.network

import android.content.Context
import androidx.room.Room
import com.ferforastieri.valkyris.core.database.ValkyrisDatabase
import com.ferforastieri.valkyris.core.security.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun api(session:SessionStore,@ApplicationContext context:Context)=ValkyrisApi({session.get()}){context.getString(it)}
    @Provides @Singleton fun database(@ApplicationContext context:Context)=Room.databaseBuilder(context,ValkyrisDatabase::class.java,"valkyris-cache.db").fallbackToDestructiveMigration(false).build()
    @Provides fun dao(database:ValkyrisDatabase)=database.dao()
}
