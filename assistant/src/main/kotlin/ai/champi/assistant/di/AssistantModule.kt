package ai.champi.assistant.di

import ai.champi.assistant.RoutingPolicy
import ai.champi.core.persistence.RoutingDecisionDao
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.providers.api.LlmProvider
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.TtsProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AssistantModule {
    /**
     * Provides [RoutingPolicy] by wrapping the currently registered single-instance providers into
     * lists. This avoids requiring Hilt multibinding setup (which would conflict with the existing
     * `@Binds` declarations for the single-provider bindings used by [ai.champi.assistant.TurnOrchestrator]).
     * When additional provider implementations are registered in future issues, update this method
     * to include them in the lists.
     */
    @Provides
    @Singleton
    fun provideRoutingPolicy(
        llmProvider: LlmProvider,
        sttProvider: SttProvider,
        ttsProvider: TtsProvider,
        routingDecisionDao: RoutingDecisionDao,
        routingSettingsRepository: RoutingSettingsRepository,
    ): RoutingPolicy = RoutingPolicy(
        llmProviders = listOf(llmProvider),
        sttProviders = listOf(sttProvider),
        ttsProviders = listOf(ttsProvider),
        routingDecisionDao = routingDecisionDao,
        routingSettingsRepository = routingSettingsRepository,
    )
}
