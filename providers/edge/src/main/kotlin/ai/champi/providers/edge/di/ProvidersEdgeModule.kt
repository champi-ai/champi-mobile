package ai.champi.providers.edge.di

import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.TtsProvider
import ai.champi.providers.api.VadProvider
import ai.champi.providers.edge.AndroidTtsProvider
import ai.champi.providers.edge.EdgeSttProvider
import ai.champi.providers.edge.EdgeVadProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvidersEdgeModule {
    @Binds
    abstract fun bindTtsProvider(impl: AndroidTtsProvider): TtsProvider

    @Binds
    abstract fun bindVadProvider(impl: EdgeVadProvider): VadProvider

    @Binds
    abstract fun bindSttProvider(impl: EdgeSttProvider): SttProvider
}
