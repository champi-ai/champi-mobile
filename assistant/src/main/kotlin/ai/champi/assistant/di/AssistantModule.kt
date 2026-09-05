package ai.champi.assistant.di

import ai.champi.actions.AlarmTimerActionProvider
import ai.champi.actions.CalendarActionProvider
import ai.champi.assistant.RoutingPolicy
import ai.champi.assistant.TurnOrchestrator
import ai.champi.core.persistence.QueuedTurnDao
import ai.champi.core.persistence.RoutingDecisionDao
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.core.state.AppStateHolder
import ai.champi.providers.api.ActionProvider
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
     * lists. This avoids requiring Hilt multibinding setup. When additional provider implementations
     * are registered in future issues, update this method to include them in the lists.
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

    /**
     * Provides all registered [ActionProvider]s as a list passed to [TurnOrchestrator].
     * Following the same explicit-list pattern as [provideRoutingPolicy] to avoid multibinding
     * conflicts. Add new [ActionProvider] implementations here as they are created.
     */
    @Provides
    @Singleton
    fun provideActionProviders(
        alarmTimerActionProvider: AlarmTimerActionProvider,
        calendarActionProvider: CalendarActionProvider,
    ): @JvmSuppressWildcards List<ActionProvider> = listOf(alarmTimerActionProvider, calendarActionProvider)

    /**
     * Provides [TurnOrchestrator] explicitly so the [List<ActionProvider>] parameter (not a
     * directly injectable Hilt type) can be forwarded from [provideActionProviders].
     */
    @Provides
    @Singleton
    fun provideTurnOrchestrator(
        conversationManager: ai.champi.assistant.ConversationManager,
        routingPolicy: RoutingPolicy,
        routingSettingsRepository: RoutingSettingsRepository,
        queuedTurnDao: QueuedTurnDao,
        appStateHolder: AppStateHolder,
        actionProviders: @JvmSuppressWildcards List<ActionProvider>,
    ): TurnOrchestrator = TurnOrchestrator(
        conversationManager = conversationManager,
        routingPolicy = routingPolicy,
        routingSettingsRepository = routingSettingsRepository,
        queuedTurnDao = queuedTurnDao,
        appStateHolder = appStateHolder,
        actionProviders = actionProviders,
    )
}
