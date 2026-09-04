package ai.champi.providers.edge.di

import ai.champi.providers.api.TtsProvider
import ai.champi.providers.edge.AndroidTtsProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvidersEdgeModule {
    @Binds
    abstract fun bindTtsProvider(impl: AndroidTtsProvider): TtsProvider
}
