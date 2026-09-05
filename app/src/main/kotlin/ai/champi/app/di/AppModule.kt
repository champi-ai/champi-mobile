package ai.champi.app.di

import ai.champi.app.ProactiveNotificationPosterImpl
import ai.champi.core.notification.ProactiveNotificationPoster
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    /** Binds [ProactiveNotificationPosterImpl] to the [ProactiveNotificationPoster] interface so
     *  that `:assistant`'s [ai.champi.assistant.ProactiveNotificationEngine] can post system-tray
     *  notifications without depending on `:app`. */
    @Binds
    @Singleton
    abstract fun bindProactiveNotificationPoster(
        impl: ProactiveNotificationPosterImpl,
    ): ProactiveNotificationPoster
}
