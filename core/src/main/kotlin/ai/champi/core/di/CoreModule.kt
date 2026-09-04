package ai.champi.core.di

import ai.champi.core.persistence.AppDatabase
import ai.champi.core.persistence.MIGRATION_1_2
import ai.champi.core.persistence.MIGRATION_2_3
import ai.champi.core.persistence.MessageDao
import ai.champi.core.persistence.QueuedTurnDao
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "champi.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideQueuedTurnDao(database: AppDatabase): QueuedTurnDao = database.queuedTurnDao()
}
