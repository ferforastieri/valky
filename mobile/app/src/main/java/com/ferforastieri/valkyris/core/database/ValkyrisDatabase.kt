package com.ferforastieri.valkyris.core.database

import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName="events") data class EventEntity(@PrimaryKey val id:String,val cameraId:String,val type:String,val confidence:Double,val occurredAt:String,val snapshotPath:String?,val clipPath:String?,val acknowledgedAt:String?)
@Entity(tableName="rules") data class RuleEntity(@PrimaryKey val id:String,val cameraId:String,val name:String,val detectorTypes:String,val enabled:Boolean)
@Dao interface ValkyrisDao {
    @Query("SELECT * FROM events ORDER BY occurredAt DESC") fun events():Flow<List<EventEntity>>
    @Query("SELECT * FROM rules ORDER BY name") fun rules():Flow<List<RuleEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun replaceEvents(items:List<EventEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun replaceRules(items:List<RuleEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveRule(item:RuleEntity)
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Transaction suspend fun syncEvents(items:List<EventEntity>){clearEvents();replaceEvents(items)}
}
@Database(entities=[EventEntity::class,RuleEntity::class],version=4,exportSchema=true)
abstract class ValkyrisDatabase:RoomDatabase(){abstract fun dao():ValkyrisDao}

val MIGRATION_3_4=Migration(3,4){database->database.execSQL("DROP TABLE IF EXISTS pending_actions")}
