package ai.champi.providers.remote.di

import ai.champi.providers.api.LlmProvider
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.TtsProvider
import ai.champi.providers.remote.RemoteLlmProvider
import ai.champi.providers.remote.RemoteSttProvider
import ai.champi.providers.remote.RemoteTtsProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvidersRemoteModule {
    @Binds
    abstract fun bindLlmProvider(impl: RemoteLlmProvider): LlmProvider

    @Binds
    abstract fun bindSttProvider(impl: RemoteSttProvider): SttProvider

    @Binds
    abstract fun bindTtsProvider(impl: RemoteTtsProvider): TtsProvider
}
