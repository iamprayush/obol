package dev.prayush.obol.data

import android.content.Context
import android.net.Uri
import android.os.UserManager
import android.provider.OpenableColumns
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.model.sumPaise
import dev.prayush.obol.domain.policy.AlarmSlotConfig
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.PauseState
import dev.prayush.obol.domain.policy.PaymentDestinationState
import dev.prayush.obol.domain.policy.PendingLock
import dev.prayush.obol.domain.time.ObolClock
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class BackupSummary(
    val totalMornings: Int,
    val totalChargedRupees: Long,
    val totalUnpaidRupees: Long,
    val avgUpMinutes: Long? = null,
)

@Serializable
data class BackupPreferencesSnapshot(
    val welcomeAccepted: Boolean = false,
    val permissionsAccepted: Boolean = false,
    val setupCompletedAtEpochMs: Long? = null,
    val lastReconciledAtEpochMs: Long? = null,
    val pauseState: PauseState = PauseState(),
    val destinationState: PaymentDestinationState = PaymentDestinationState(),
    val alarmSound: AlarmSoundChoice = AlarmSoundChoice.Default,
    val lastSystemAlarm: AlarmSoundChoice.SystemAlarm? = null,
)

@Serializable
data class BackupAlarmSlot(
    val dayOfWeek: Int,
    val activeEnabled: Boolean,
    val activeHour: Int,
    val activeMinute: Int,
    val pendingJson: String? = null,
)

@Serializable
data class BackupGraceNight(
    val scheduledAtEpochMs: Long,
    val calendarMonth: String,
    val declaredAtEpochMs: Long,
)

@Serializable
data class BackupMorningHeader(
    val morningId: String,
    val calendarDate: String,
    val scheduledAtEpochMs: Long,
    val isTrialDay: Boolean = false,
    val isGraceNight: Boolean,
    val hadPriorDebt: Boolean,
    val priorMonthChargedPaise: Long,
    val ratePaisePerMin: Long = ObolConstants.RATE_PAISE_PER_MIN,
    val morningCapPaise: Long = ObolConstants.MORNING_CAP_PAISE.value,
    val monthlyCapPaise: Long = ObolConstants.MONTHLY_CAP_PAISE.value,
)

@Serializable
data class BackupMorningEvent(
    val seq: Int,
    val atEpochMs: Long,
    val bootCount: Long,
    val elapsedMs: Long,
    val payloadJson: String,
)

@Serializable
data class BackupMorning(
    val header: BackupMorningHeader,
    val events: List<BackupMorningEvent>,
)

@Serializable
data class ObolBackupDocument(
    val schemaVersion: Int = ObolBackupCodec.CURRENT_SCHEMA_VERSION,
    val appVersion: String = "2.0.0",
    val exportedAtEpochMs: Long,
    val summary: BackupSummary? = null,
    val preferences: BackupPreferencesSnapshot,
    val alarmSlots: List<BackupAlarmSlot>,
    val graceNights: List<BackupGraceNight>,
    val mornings: List<BackupMorning>,
)

object ObolBackupCodec {
    const val CURRENT_SCHEMA_VERSION: Int = 1

    private val prettyJson: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(doc: ObolBackupDocument): String = prettyJson.encodeToString(doc)

    fun decodeAndValidate(rawJson: String): Result<ObolBackupDocument> = runCatching {
        if (rawJson.isBlank()) {
            throw IllegalArgumentException("Backup file is empty.")
        }
        val rootObj = prettyJson.parseToJsonElement(rawJson) as? JsonObject
            ?: throw IllegalArgumentException("Backup file is not a valid JSON object.")
        val versionPrimitive = rootObj["schemaVersion"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Missing schemaVersion in backup file.")
        val schemaVersion = versionPrimitive.intOrNull
            ?: throw IllegalArgumentException("Invalid schemaVersion in backup file.")
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $schemaVersion.")
        }

        val doc = prettyJson.decodeFromString<ObolBackupDocument>(rawJson)
        validateDocument(doc)
        doc
    }

    private fun validateDocument(doc: ObolBackupDocument) {
        if (doc.exportedAtEpochMs <= 0L) {
            throw IllegalArgumentException("Invalid backup timestamp.")
        }

        val seenDays = mutableSetOf<Int>()
        for (slot in doc.alarmSlots) {
            if (slot.dayOfWeek !in 1..7) {
                throw IllegalArgumentException("Invalid alarm dayOfWeek: ${slot.dayOfWeek}")
            }
            if (!seenDays.add(slot.dayOfWeek)) {
                throw IllegalArgumentException("Duplicate alarm slot for day ${slot.dayOfWeek}")
            }
            if (slot.activeHour !in 0..23 || slot.activeMinute !in 0..59) {
                throw IllegalArgumentException("Invalid alarm time ${slot.activeHour}:${slot.activeMinute}")
            }
            slot.pendingJson?.let { raw ->
                ObolJson.decodeFromString<PendingLock<AlarmSlotConfig>>(raw)
            }
        }

        val seenGrace = mutableSetOf<Long>()
        for (grace in doc.graceNights) {
            if (grace.calendarMonth.isBlank()) {
                throw IllegalArgumentException("Blank calendarMonth in grace nights.")
            }
            if (!seenGrace.add(grace.scheduledAtEpochMs)) {
                throw IllegalArgumentException("Duplicate grace night timestamp: ${grace.scheduledAtEpochMs}")
            }
        }

        val seenMorningIds = mutableSetOf<String>()
        for (morning in doc.mornings) {
            val h = morning.header
            if (h.morningId.isBlank()) {
                throw IllegalArgumentException("Blank morningId in backup.")
            }
            if (!seenMorningIds.add(h.morningId)) {
                throw IllegalArgumentException("Duplicate morningId in backup: ${h.morningId}")
            }
            LocalDate.parse(h.calendarDate)
            if (h.ratePaisePerMin < 0L || h.morningCapPaise < 0L || h.monthlyCapPaise < 0L) {
                throw IllegalArgumentException("Negative rate/cap values in morning ${h.morningId}")
            }
            val seenSeqs = mutableSetOf<Int>()
            for (ev in morning.events) {
                if (ev.seq <= 0 || !seenSeqs.add(ev.seq)) {
                    throw IllegalArgumentException("Invalid or duplicate event seq ${ev.seq} in ${h.morningId}")
                }
                ObolJson.decodeFromString<MorningEvent>(ev.payloadJson)
            }
        }
    }

    fun buildSummary(
        snapshot: AppSnapshot,
        morningsCount: Int,
    ): BackupSummary {
        val totalChargedPaise = snapshot.mornings.map { it.morningChargedPaise }.sumPaise()
        val upDurations = snapshot.mornings.mapNotNull { it.upDurationMinutes }
        val avgUp = if (upDurations.isNotEmpty()) {
            upDurations.average().roundToLong()
        } else {
            null
        }
        return BackupSummary(
            totalMornings = morningsCount,
            totalChargedRupees = totalChargedPaise.rupees,
            totalUnpaidRupees = snapshot.totalUnpaidPaise.rupees,
            avgUpMinutes = avgUp,
        )
    }
}

interface BackupUriIo {
    fun writeBytes(uri: Uri, bytes: ByteArray)
    fun readBytes(uri: Uri): ByteArray
    fun queryDisplayName(uri: Uri): String?
    fun canReadUri(uri: Uri): Boolean
}

class ContentResolverBackupUriIo(
    private val appContext: Context,
) : BackupUriIo {

    override fun writeBytes(uri: Uri, bytes: ByteArray) {
        val resolver = appContext.contentResolver
        // Google Drive's DocumentsProvider (com.google.android.apps.docs.storage) supports "rwt"
        // where "wt" can throw FileNotFoundException("Unsupported mode: wt"). Try "rwt" first,
        // then fall back to "wt" and "w".
        val modes = listOf("rwt", "wt", "w")
        var lastError: Throwable? = null
        for (mode in modes) {
            try {
                val pfd = resolver.openFileDescriptor(uri, mode)
                if (pfd != null) {
                    pfd.use { descriptor ->
                        FileOutputStream(descriptor.fileDescriptor).use { out ->
                            out.write(bytes)
                            out.flush()
                            runCatching { descriptor.fileDescriptor.sync() }
                        }
                    }
                    return
                }
            } catch (sec: SecurityException) {
                throw sec
            } catch (e: FileNotFoundException) {
                lastError = e
            } catch (e: UnsupportedOperationException) {
                lastError = e
            } catch (e: IllegalArgumentException) {
                lastError = e
            }
        }

        // Last resort: ContentResolver.openOutputStream
        try {
            val out = resolver.openOutputStream(uri, "rwt")
                ?: resolver.openOutputStream(uri, "wt")
                ?: resolver.openOutputStream(uri)
                ?: throw IOException("Unable to open backup file for writing.")
            out.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        } catch (sec: SecurityException) {
            throw sec
        } catch (e: Throwable) {
            throw IOException(
                lastError?.message ?: e.message ?: "Failed to write backup file.",
                e,
            )
        }
    }

    override fun readBytes(uri: Uri): ByteArray {
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open backup file for reading.")
        return stream.use { it.readBytes() }
    }

    override fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
            } else {
                null
            }
        }
    }.getOrNull()

    override fun canReadUri(uri: Uri): Boolean = runCatching {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}

sealed interface BackupOpResult {
    data class Success(val syncedAt: Instant, val morningCount: Int) : BackupOpResult
    data object NotConfigured : BackupOpResult
    data object DeferredLocked : BackupOpResult
    data class Failure(val reason: String, val needsReconnect: Boolean = false) : BackupOpResult
}

data class BackupPreview(
    val uriString: String,
    val displayName: String,
    val document: ObolBackupDocument,
    val morningCount: Int,
    val alarmCount: Int,
    val exportedAt: Instant,
)

class ObolBackupManager(
    private val appContext: Context,
    private val dpContext: Context,
    private val repository: ObolRepository,
    private val preferences: ObolPreferences,
    private val clock: ObolClock,
    private val scope: CoroutineScope,
    private val uriIo: BackupUriIo = ContentResolverBackupUriIo(appContext),
    private val autoBackupDebounceMs: Long = 1_500L,
) {
    private val backupMutex = Mutex()
    private val debounceLock = Any()
    private var debounceJob: Job? = null

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun isUserUnlocked(): Boolean = runCatching {
        dpContext.getSystemService(UserManager::class.java)?.isUserUnlocked
    }.getOrNull() ?: true

    fun queryDisplayName(uri: Uri): String =
        uriIo.queryDisplayName(uri) ?: "obol-backup.json"

    fun scheduleAutoBackup() {
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = scope.launch(Dispatchers.IO) {
                val prefs = runCatching { preferences.current() }.getOrNull() ?: return@launch
                if (prefs.backupUri.isNullOrBlank()) return@launch
                preferences.markBackupDirty()
                repository.notifyClockTick()
                if (autoBackupDebounceMs > 0L) {
                    delay(autoBackupDebounceMs)
                }
                flushIfDirty()
            }
        }
    }

    suspend fun flushIfDirty(): BackupOpResult {
        if (!isUserUnlocked()) return BackupOpResult.DeferredLocked
        val prefs = preferences.current()
        if (prefs.backupUri.isNullOrBlank()) return BackupOpResult.NotConfigured
        if (!prefs.backupDirty) {
            return prefs.lastBackupAt?.let {
                BackupOpResult.Success(it, repository.currentSnapshotNow().mornings.size)
            } ?: BackupOpResult.NotConfigured
        }
        return backupNow()
    }

    suspend fun linkAndBackup(uri: Uri, fallbackName: String = "obol-backup.json"): BackupOpResult {
        val name = uriIo.queryDisplayName(uri) ?: fallbackName
        preferences.saveBackupTarget(uri.toString(), name)
        repository.notifyClockTick()
        return backupNow()
    }

    suspend fun disconnectBackup() = backupMutex.withLock {
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = null
        }
        preferences.clearBackupTarget()
        repository.notifyClockTick()
    }

    suspend fun backupNow(): BackupOpResult = backupMutex.withLock {
        if (!isUserUnlocked()) {
            val prefs = preferences.current()
            if (!prefs.backupUri.isNullOrBlank()) {
                preferences.markBackupDirty()
                repository.notifyClockTick()
            }
            return@withLock BackupOpResult.DeferredLocked
        }
        val prefs = preferences.current()
        val uriString = prefs.backupUri
        if (uriString.isNullOrBlank()) {
            return@withLock BackupOpResult.NotConfigured
        }
        val uri = Uri.parse(uriString)
        _isSyncing.value = true
        try {
            val doc = repository.captureBackupDocument()
            val jsonText = ObolBackupCodec.encode(doc)
            val bytes = jsonText.toByteArray(Charsets.UTF_8)

            withContext(Dispatchers.IO) {
                uriIo.writeBytes(uri, bytes)
                val readBackText = uriIo.readBytes(uri).toString(Charsets.UTF_8)
                val verified = ObolBackupCodec.decodeAndValidate(readBackText).getOrElse { err ->
                    throw IOException("Backup verification failed: ${err.message}", err)
                }
                if (verified.exportedAtEpochMs != doc.exportedAtEpochMs ||
                    verified.mornings.size != doc.mornings.size
                ) {
                    throw IOException("Backup read-back mismatch.")
                }
            }

            val syncedAt = Instant.ofEpochMilli(doc.exportedAtEpochMs)
            preferences.recordBackupSuccess(syncedAt)
            repository.notifyClockTick()
            BackupOpResult.Success(syncedAt = syncedAt, morningCount = doc.mornings.size)
        } catch (sec: SecurityException) {
            val msg = "Drive permission expired. Reconnect file."
            preferences.recordBackupFailure(msg)
            repository.notifyClockTick()
            BackupOpResult.Failure(reason = msg, needsReconnect = true)
        } catch (t: Throwable) {
            val msg = t.message?.takeIf { it.isNotBlank() } ?: "Backup failed."
            preferences.recordBackupFailure(msg)
            repository.notifyClockTick()
            BackupOpResult.Failure(reason = msg, needsReconnect = false)
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun inspectBackupUri(uri: Uri): Result<BackupPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val rawText = uriIo.readBytes(uri).toString(Charsets.UTF_8)
            val doc = ObolBackupCodec.decodeAndValidate(rawText).getOrThrow()
            val name = uriIo.queryDisplayName(uri) ?: "obol-backup.json"
            BackupPreview(
                uriString = uri.toString(),
                displayName = name,
                document = doc,
                morningCount = doc.mornings.size,
                alarmCount = doc.alarmSlots.count { it.activeEnabled },
                exportedAt = Instant.ofEpochMilli(doc.exportedAtEpochMs),
            )
        }
    }

    suspend fun restoreFromPreview(
        preview: BackupPreview,
        resetPermissionsForNewPhone: Boolean,
    ): Result<Int> = backupMutex.withLock {
        runCatching {
            val rawSound = preview.document.preferences.alarmSound
            val safeAlarmSound = if (rawSound is AlarmSoundChoice.SystemAlarm) {
                val parsedUri = runCatching { Uri.parse(rawSound.uriString) }.getOrNull()
                if (parsedUri != null && uriIo.canReadUri(parsedUri)) {
                    rawSound
                } else {
                    AlarmSoundChoice.Default
                }
            } else {
                rawSound
            }
            val safeLastSystem = preview.document.preferences.lastSystemAlarm?.takeIf { sys ->
                val parsedUri = runCatching { Uri.parse(sys.uriString) }.getOrNull()
                parsedUri != null && uriIo.canReadUri(parsedUri)
            }
            val sanitizedDoc = preview.document.copy(
                preferences = preview.document.preferences.copy(
                    alarmSound = safeAlarmSound,
                    lastSystemAlarm = safeLastSystem,
                ),
            )
            repository.restoreFromBackupDocument(
                doc = sanitizedDoc,
                resetPermissionsForNewPhone = resetPermissionsForNewPhone,
            )
            sanitizedDoc.mornings.size
        }
    }
}
