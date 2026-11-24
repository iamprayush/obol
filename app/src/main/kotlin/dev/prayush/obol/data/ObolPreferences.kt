package dev.prayush.obol.data

import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.PauseState
import dev.prayush.obol.domain.policy.PaymentDestinationState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

enum class OnboardingStep {
    WELCOME,
    PERMISSIONS,
    ALARMS,
    COMPLETE,
}

data class StoredPreferences(
    val welcomeAccepted: Boolean = false,
    val permissionsAccepted: Boolean = false,
    val setupCompletedAt: Instant? = null,
    val lastReconciledAt: Instant? = null,
    val pauseState: PauseState = PauseState(),
    val destinationState: PaymentDestinationState = PaymentDestinationState(),
    val alarmSound: AlarmSoundChoice = AlarmSoundChoice.Default,
    val lastSystemAlarm: AlarmSoundChoice.SystemAlarm? = null,
    val backupUri: String? = null,
    val backupDisplayName: String? = null,
    val lastBackupAt: Instant? = null,
    val lastBackupError: String? = null,
    val backupDirty: Boolean = false,
) {
    val onboardingStep: OnboardingStep
        get() = when {
            setupCompletedAt != null -> OnboardingStep.COMPLETE
            permissionsAccepted -> OnboardingStep.ALARMS
            welcomeAccepted -> OnboardingStep.PERMISSIONS
            else -> OnboardingStep.WELCOME
        }
}

class ObolPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    val preferencesFlow: Flow<StoredPreferences> = dataStore.data.map { prefs ->
        val parsedAlarmSound = prefs[KEY_ALARM_SOUND_JSON]?.let {
            runCatching { ObolJson.decodeFromString<AlarmSoundChoice>(it) }.getOrNull()
        } ?: AlarmSoundChoice.Default
        val parsedLastSystem = prefs[KEY_LAST_SYSTEM_ALARM_JSON]?.let {
            runCatching { ObolJson.decodeFromString<AlarmSoundChoice.SystemAlarm>(it) }.getOrNull()
        } ?: (parsedAlarmSound as? AlarmSoundChoice.SystemAlarm)
        StoredPreferences(
            welcomeAccepted = prefs[KEY_WELCOME_ACCEPTED] ?: false,
            permissionsAccepted = prefs[KEY_PERMISSIONS_ACCEPTED] ?: false,
            setupCompletedAt = prefs[KEY_SETUP_COMPLETED_AT]?.let { Instant.ofEpochMilli(it) },
            lastReconciledAt = prefs[KEY_LAST_RECONCILED_AT]?.let { Instant.ofEpochMilli(it) },
            pauseState = prefs[KEY_PAUSE_LOCK_JSON]?.let {
                runCatching { ObolJson.decodeFromString<PauseState>(it) }.getOrNull()
            } ?: PauseState(),
            destinationState = prefs[KEY_DESTINATION_LOCK_JSON]?.let {
                runCatching { ObolJson.decodeFromString<PaymentDestinationState>(it) }.getOrNull()
            } ?: PaymentDestinationState(),
            alarmSound = parsedAlarmSound,
            lastSystemAlarm = parsedLastSystem,
            backupUri = prefs[KEY_BACKUP_URI],
            backupDisplayName = prefs[KEY_BACKUP_DISPLAY_NAME],
            lastBackupAt = prefs[KEY_LAST_BACKUP_AT]?.let { Instant.ofEpochMilli(it) },
            lastBackupError = prefs[KEY_LAST_BACKUP_ERROR],
            backupDirty = prefs[KEY_BACKUP_DIRTY] ?: false,
        )
    }

    suspend fun current(): StoredPreferences = preferencesFlow.first()

    suspend fun markWelcomeAccepted() {
        dataStore.edit { it[KEY_WELCOME_ACCEPTED] = true }
    }

    suspend fun markPermissionsAccepted() {
        dataStore.edit {
            it[KEY_WELCOME_ACCEPTED] = true
            it[KEY_PERMISSIONS_ACCEPTED] = true
        }
    }

    suspend fun markSetupCompleted(at: Instant) {
        dataStore.edit {
            it[KEY_WELCOME_ACCEPTED] = true
            it[KEY_PERMISSIONS_ACCEPTED] = true
            if (it[KEY_SETUP_COMPLETED_AT] == null) {
                it[KEY_SETUP_COMPLETED_AT] = at.toEpochMilli()
            }
            if (it[KEY_LAST_RECONCILED_AT] == null) {
                it[KEY_LAST_RECONCILED_AT] = at.toEpochMilli()
            }
        }
    }

    suspend fun updateLastReconciledAt(at: Instant) {
        dataStore.edit { it[KEY_LAST_RECONCILED_AT] = at.toEpochMilli() }
    }

    suspend fun savePauseState(state: PauseState) {
        dataStore.edit {
            it[KEY_PAUSE_LOCK_JSON] = ObolJson.encodeToString(state)
        }
    }

    suspend fun saveDestinationState(state: PaymentDestinationState) {
        dataStore.edit {
            it[KEY_DESTINATION_LOCK_JSON] = ObolJson.encodeToString(state)
        }
    }

    suspend fun saveAlarmSound(choice: AlarmSoundChoice) {
        dataStore.edit {
            it[KEY_ALARM_SOUND_JSON] = ObolJson.encodeToString(choice)
            if (choice is AlarmSoundChoice.SystemAlarm) {
                it[KEY_LAST_SYSTEM_ALARM_JSON] = ObolJson.encodeToString(choice)
            }
        }
    }

    suspend fun saveBackupTarget(uri: String, displayName: String) {
        dataStore.edit {
            it[KEY_BACKUP_URI] = uri
            it[KEY_BACKUP_DISPLAY_NAME] = displayName
            it.remove(KEY_LAST_BACKUP_ERROR)
        }
    }

    suspend fun clearBackupTarget() {
        dataStore.edit {
            it.remove(KEY_BACKUP_URI)
            it.remove(KEY_BACKUP_DISPLAY_NAME)
            it.remove(KEY_LAST_BACKUP_AT)
            it.remove(KEY_LAST_BACKUP_ERROR)
            it[KEY_BACKUP_DIRTY] = false
        }
    }

    suspend fun markBackupDirty() {
        dataStore.edit {
            it[KEY_BACKUP_DIRTY] = true
        }
    }

    suspend fun recordBackupSuccess(at: Instant) {
        dataStore.edit {
            it[KEY_LAST_BACKUP_AT] = at.toEpochMilli()
            it.remove(KEY_LAST_BACKUP_ERROR)
            it[KEY_BACKUP_DIRTY] = false
        }
    }

    suspend fun recordBackupFailure(error: String) {
        dataStore.edit {
            it[KEY_LAST_BACKUP_ERROR] = error
            it[KEY_BACKUP_DIRTY] = true
        }
    }

    suspend fun restoreDomainPreferences(
        welcomeAccepted: Boolean,
        permissionsAccepted: Boolean,
        setupCompletedAt: Instant?,
        lastReconciledAt: Instant?,
        pauseState: PauseState,
        destinationState: PaymentDestinationState,
        alarmSound: AlarmSoundChoice,
        lastSystemAlarm: AlarmSoundChoice.SystemAlarm?,
    ) {
        dataStore.edit {
            it[KEY_WELCOME_ACCEPTED] = welcomeAccepted
            it[KEY_PERMISSIONS_ACCEPTED] = permissionsAccepted
            if (setupCompletedAt != null) {
                it[KEY_SETUP_COMPLETED_AT] = setupCompletedAt.toEpochMilli()
            } else {
                it.remove(KEY_SETUP_COMPLETED_AT)
            }
            if (lastReconciledAt != null) {
                it[KEY_LAST_RECONCILED_AT] = lastReconciledAt.toEpochMilli()
            } else {
                it.remove(KEY_LAST_RECONCILED_AT)
            }
            it[KEY_PAUSE_LOCK_JSON] = ObolJson.encodeToString(pauseState)
            it[KEY_DESTINATION_LOCK_JSON] = ObolJson.encodeToString(destinationState)
            it[KEY_ALARM_SOUND_JSON] = ObolJson.encodeToString(alarmSound)
            if (lastSystemAlarm != null) {
                it[KEY_LAST_SYSTEM_ALARM_JSON] = ObolJson.encodeToString(lastSystemAlarm)
            } else {
                it.remove(KEY_LAST_SYSTEM_ALARM_JSON)
            }
            it[KEY_BACKUP_DIRTY] = false
        }
    }

    companion object {
        private val KEY_WELCOME_ACCEPTED = booleanPreferencesKey("welcome_accepted")
        private val KEY_PERMISSIONS_ACCEPTED = booleanPreferencesKey("permissions_accepted")
        private val KEY_SETUP_COMPLETED_AT = longPreferencesKey("setup_completed_at_epoch_ms")
        private val KEY_LAST_RECONCILED_AT = longPreferencesKey("last_reconciled_at_epoch_ms")
        private val KEY_PAUSE_LOCK_JSON = stringPreferencesKey("pause_lock_json")
        private val KEY_DESTINATION_LOCK_JSON = stringPreferencesKey("payment_destination_lock_json")
        private val KEY_ALARM_SOUND_JSON = stringPreferencesKey("alarm_sound_json")
        private val KEY_LAST_SYSTEM_ALARM_JSON = stringPreferencesKey("last_system_alarm_json")
        private val KEY_BACKUP_URI = stringPreferencesKey("backup_uri")
        private val KEY_BACKUP_DISPLAY_NAME = stringPreferencesKey("backup_display_name")
        private val KEY_LAST_BACKUP_AT = longPreferencesKey("last_backup_at_epoch_ms")
        private val KEY_LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        private val KEY_BACKUP_DIRTY = booleanPreferencesKey("backup_dirty")

        fun create(
            context: Context,
            scope: CoroutineScope,
            fileName: String = "obol_prefs",
        ): ObolPreferences {
            val dpContext = if (context.isDeviceProtectedStorage) {
                context
            } else {
                context.createDeviceProtectedStorageContext()
            }
            val store = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = {
                    resolveDeviceProtectedDataStoreFile(
                        dpContext = dpContext,
                        fileName = fileName,
                    )
                },
            )
            return ObolPreferences(store)
        }

        internal fun resolveDeviceProtectedDataStoreFile(
            dpContext: Context,
            fileName: String,
        ): File {
            val relativePath = "datastore/$fileName.preferences_pb"
            val deFile = File(dpContext.filesDir, relativePath)
            if (deFile.exists()) return deFile

            val isUserUnlocked = runCatching {
                dpContext.getSystemService(UserManager::class.java)?.isUserUnlocked
            }.getOrNull() == true

            if (isUserUnlocked) {
                runCatching {
                    val appCtx = dpContext.applicationContext
                    if (appCtx != null && !appCtx.isDeviceProtectedStorage) {
                        val legacyCeFile = File(appCtx.filesDir, relativePath)
                        if (legacyCeFile.exists() && legacyCeFile.canonicalPath != deFile.canonicalPath) {
                            deFile.parentFile?.mkdirs()
                            val tmpFile = File(deFile.parentFile, "${deFile.name}.migrate.tmp")
                            legacyCeFile.inputStream().use { input ->
                                tmpFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (tmpFile.renameTo(deFile)) {
                                legacyCeFile.delete()
                            } else {
                                tmpFile.delete()
                            }
                        }
                    }
                }
            }
            return deFile
        }

        fun createForFile(
            file: File,
            scope: CoroutineScope,
        ): ObolPreferences {
            val store = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            )
            return ObolPreferences(store)
        }
    }
}
