package com.opensab.app.di

import android.Manifest
import android.content.Context
import com.opensab.app.R
import com.opensab.app.source.message.AndroidSmsSender
import com.opensab.app.ui.permissions.PermissionProbe
import com.opensab.domain.contacts.ContactDirectory
import com.opensab.domain.message.DefaultMessageMenuAccess
import com.opensab.domain.message.DefaultMessageSource
import com.opensab.domain.message.MessageMenuAccess
import com.opensab.domain.message.MessageNotificationBus
import com.opensab.domain.message.MessageSource
import com.opensab.domain.message.SmsSender
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.Tunable
import com.opensab.domain.settings.resolveQuickReplies
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * The fixed replies of the reply page, already resolved to the words that will leave the phone.
 *
 * Qualified because an unqualified `StateFlow<List<String>>` is not a binding a graph can keep
 * straight — the same reason the temperature unit travels as its repository and not as a bare flow.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class QuickReplies

/** How long "Sent" holds the cluster, already clamped to its range. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SentLinger

@Module
@InstallIn(SingletonComponent::class)
object MessageModule {

    /**
     * The one message source of the session.
     *
     * It owns the unread message and its claim on the screen, so a second instance would be a
     * second owner of both.
     */
    @Provides
    @Singleton
    fun provideMessageSource(
        notifications: MessageNotificationBus
    ): MessageSource = DefaultMessageSource(notifications)

    /**
     * Where the quick replies stop being a setting and become words.
     *
     * The module sees `Context` and `R`, the engine sees neither, and what crosses is a flow of
     * strings already resolved: the rider's own, or the four the app ships translated when he has
     * written none.
     */
    @Provides
    @Singleton
    @QuickReplies
    fun provideQuickReplies(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope
    ): StateFlow<List<String>> {
        val defaults = context.resources.getStringArray(R.array.default_quick_replies).toList()
        return settingsRepository.settingsFlow
            .map { resolveQuickReplies(it.quickReplies, defaults) }
            .stateIn(applicationScope, SharingStarted.Eagerly, defaults)
    }

    /**
     * The linger, clamped on the way out and never on the way in: a value stored by an
     * older version, or edited by hand, cannot leave the cluster sitting on "Sent".
     */
    @Provides
    @Singleton
    @SentLinger
    fun provideSentLinger(
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope
    ): StateFlow<Duration> = settingsRepository.settingsFlow
        .map { Tunable.REPLY_SENT_LINGER.resolve(it) }
        .stateIn(
            applicationScope,
            SharingStarted.Eagerly,
            Tunable.REPLY_SENT_LINGER.resolve(AppSettings())
        )

    @Provides
    @Singleton
    fun provideSmsSender(@ApplicationContext context: Context): SmsSender = AndroidSmsSender(context)

    /**
     * The permissions enter as lambdas rather than as the probe itself, keeping `source/message`
     * free from framework imports and UI concerns.
     */
    @Provides
    @Singleton
    fun provideMessageMenuAccess(
        probe: PermissionProbe,
        directory: ContactDirectory
    ): MessageMenuAccess = DefaultMessageMenuAccess(
        contactsGranted = { probe.isPermissionGranted(Manifest.permission.READ_CONTACTS) },
        micGranted = { probe.isPermissionGranted(Manifest.permission.RECORD_AUDIO) },
        smsGranted = { probe.isPermissionGranted(Manifest.permission.SEND_SMS) },
        directory = directory
    )
}
