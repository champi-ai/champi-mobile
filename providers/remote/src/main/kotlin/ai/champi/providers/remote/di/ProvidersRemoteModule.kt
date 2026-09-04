package ai.champi.providers.remote.di

import ai.champi.providers.api.LlmProvider
import ai.champi.providers.remote.RemoteLlmProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvidersRemoteModule {
    @Binds
    abstract fun bindLlmProvider(impl: RemoteLlmProvider): LlmProvider
}
