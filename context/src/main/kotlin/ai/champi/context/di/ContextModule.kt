package ai.champi.context.di

import ai.champi.context.PeriodicContextProvider
import ai.champi.core.context.ContextSnapshotSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ContextModule {
    /** Binds the [PeriodicContextProvider] implementation to the [ContextSnapshotSource] interface
     *  so that `:assistant`'s `TurnOrchestrator` can inject the source without a direct dependency
     *  on `:context`. */
    @Binds
    @Singleton
    abstract fun bindContextSnapshotSource(impl: PeriodicContextProvider): ContextSnapshotSource
}
