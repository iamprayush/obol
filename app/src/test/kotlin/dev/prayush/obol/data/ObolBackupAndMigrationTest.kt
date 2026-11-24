package dev.prayush.obol.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.policy.AlarmSlotConfig
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.BuiltInChime
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.domain.time.FakeObolClock
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObolBackupAndMigrationTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private class FakeBackupUriIo : BackupUriIo {
        val storage = mutableMapOf<String, ByteArray>()
        val displayNames = mutableMapOf<String, String>()
        val readableUris = mutableSetOf<String>()
        var failWriteWithSecurityException: Boolean = false
        var failWriteWithIoError: Boolean = false

        override fun writeBytes(uri: Uri, bytes: ByteArray) {
            if (failWriteWithSecurityException) {
                throw SecurityException("Permission revoked")
            }
            if (failWriteWithIoError) {
                throw IOException("Simulated Drive offline failure")
            }
            storage[uri.toString()] = bytes.copyOf()
        }

        override fun readBytes(uri: Uri): ByteArray {
            return storage[uri.toString()]?.copyOf()
                ?: throw IOException("No stored content for $uri")
        }

        override fun queryDisplayName(uri: Uri): String? = displayNames[uri.toString()]

        override fun canReadUri(uri: Uri): Boolean = uri.toString() in readableUris
    }

    @Test
    fun `Room MIGRATION_1_2 upgrades v1 SQLite database in-place without losing mornings, events, slots, or grace nights`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dpContext = app.createDeviceProtectedStorageContext()
        val dbName = "migration_1_2_${System.nanoTime()}.db"
        val dbFile = dpContext.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Create an exact v1 SQLite database matching schemas/1.json
        val v1Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            v1Db.execSQL(
                "CREATE TABLE IF NOT EXISTS `morning_headers` (`morningId` TEXT NOT NULL, `calendarDate` TEXT NOT NULL, `scheduledAtEpochMs` INTEGER NOT NULL, `isTrialDay` INTEGER NOT NULL, `isGraceNight` INTEGER NOT NULL, `hadPriorDebt` INTEGER NOT NULL, `priorMonthChargedPaise` INTEGER NOT NULL, PRIMARY KEY(`morningId`))",
            )
            v1Db.execSQL(
                "CREATE TABLE IF NOT EXISTS `morning_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `morningId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `atEpochMs` INTEGER NOT NULL, `bootCount` INTEGER NOT NULL, `elapsedMs` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, FOREIGN KEY(`morningId`) REFERENCES `morning_headers`(`morningId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            v1Db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_morning_events_morningId_seq` ON `morning_events` (`morningId`, `seq`)",
            )
            v1Db.execSQL(
                "CREATE TABLE IF NOT EXISTS `alarm_slots` (`dayOfWeek` INTEGER NOT NULL, `activeEnabled` INTEGER NOT NULL, `activeHour` INTEGER NOT NULL, `activeMinute` INTEGER NOT NULL, `pendingJson` TEXT, PRIMARY KEY(`dayOfWeek`))",
            )
            v1Db.execSQL(
                "CREATE TABLE IF NOT EXISTS `grace_nights` (`scheduledAtEpochMs` INTEGER NOT NULL, `calendarMonth` TEXT NOT NULL, `declaredAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`scheduledAtEpochMs`))",
            )
            v1Db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            v1Db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'cbac7124b86cc02bdb61ffd3925da1c9')")

            // Seed real v1 rows
            val schedMs = Instant.parse("2026-11-24T00:30:00Z").toEpochMilli()
            v1Db.execSQL(
                "INSERT INTO morning_headers VALUES ('2026-11-24', '2026-11-24', $schedMs, 0, 0, 0, 0)",
            )
            v1Db.execSQL(
                "INSERT INTO morning_events (morningId, seq, atEpochMs, bootCount, elapsedMs, payloadJson) VALUES ('2026-11-24', 1, $schedMs, 1, 1000, '{\"type\":\"AlarmFired\"}')",
            )
            v1Db.execSQL(
                "INSERT INTO alarm_slots VALUES (3, 1, 6, 0, NULL)",
            )
            v1Db.execSQL(
                "INSERT INTO grace_nights VALUES (${schedMs + 86_400_000L}, '2026-11', $schedMs)",
            )
            v1Db.version = 1
        } finally {
            v1Db.close()
        }

        val migrated = Room.databaseBuilder(dpContext, ObolDatabase::class.java, dbName)
            .addMigrations(ObolDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val mornings = migrated.obolDao().loadAllMorningsWithEvents()
            assertEquals(1, mornings.size)
            assertEquals("2026-11-24", mornings[0].header.morningId)
            assertEquals(ObolConstants.RATE_PAISE_PER_MIN, mornings[0].header.ratePaisePerMin)
            assertEquals(ObolConstants.MORNING_CAP_PAISE.value, mornings[0].header.morningCapPaise)
            assertEquals(ObolConstants.MONTHLY_CAP_PAISE.value, mornings[0].header.monthlyCapPaise)
            assertEquals(1, mornings[0].events.size)

            val slots = migrated.obolDao().loadAlarmSlots()
            assertEquals(1, slots.size)
            assertEquals(6, slots[0].activeHour)
            assertEquals(0, slots[0].activeMinute)

            val grace = migrated.obolDao().loadGraceNights()
            assertEquals(1, grace.size)
            assertEquals("2026-11", grace[0].calendarMonth)
        } finally {
            migrated.close()
            dpContext.deleteDatabase(dbName)
        }
    }

    @Test
    fun `Google Drive JSON backup round-trips all data losslessly, rejects corrupt JSON, handles revoked permission and Direct Boot`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dpContext = app.createDeviceProtectedStorageContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val db1 = ObolDatabase.create(dpContext, inMemory = true)
        val prefsFile1 = File(dpContext.cacheDir, "backup_src_${System.nanoTime()}.preferences_pb")
        val prefs1 = ObolPreferences.createForFile(prefsFile1, scope)
        val clock = FakeObolClock(
            initialInstant = Instant.parse("2026-11-17T00:40:00Z"),
            initialZone = zone,
        )
        val repo1 = ObolRepository(db1.obolDao(), prefs1, clock, scope)
        val fakeIo = FakeBackupUriIo()
        val driveUri = Uri.parse("content://com.google.android.apps.docs.storage/document/obol-backup.json")
        fakeIo.displayNames[driveUri.toString()] = "obol-backup.json"

        val manager1 = ObolBackupManager(
            appContext = app,
            dpContext = dpContext,
            repository = repo1,
            preferences = prefs1,
            clock = clock,
            scope = scope,
            uriIo = fakeIo,
            autoBackupDebounceMs = 0L,
        )

        val db2 = ObolDatabase.create(dpContext, inMemory = true)
        val prefsFile2 = File(dpContext.cacheDir, "backup_dst_${System.nanoTime()}.preferences_pb")
        val prefs2 = ObolPreferences.createForFile(prefsFile2, scope)
        val repo2 = ObolRepository(db2.obolDao(), prefs2, clock, scope)
        val manager2 = ObolBackupManager(
            appContext = app,
            dpContext = dpContext,
            repository = repo2,
            preferences = prefs2,
            clock = clock,
            scope = scope,
            uriIo = fakeIo,
            autoBackupDebounceMs = 0L,
        )

        try {
            // 1. Seed rich state on phone 1: setup, custom alarm, Donorbox, custom sound, historical ₹10/min morning + current ₹20/min morning + grace night
            repo1.finishSetup()
            repo1.updateAlarmSlot(DayOfWeek.WEDNESDAY, AlarmSlotConfig(enabled = true, time = LocalTime.of(5, 45)))
            repo1.updatePaymentDestination(PaymentDestination.DonorboxOppia)
            val missingOnNewPhoneSound = AlarmSoundChoice.SystemAlarm("content://media/internal/audio/999", "Pixel Dawn")
            repo1.updateAlarmSound(missingOnNewPhoneSound)

            // Insert an older October morning with historical ₹10/min rate
            val octSched = Instant.parse("2026-10-15T00:40:00Z")
            db1.obolDao().insertMorningHeader(
                MorningHeaderEntity(
                    morningId = "2026-10-15",
                    calendarDate = "2026-10-15",
                    scheduledAtEpochMs = octSched.toEpochMilli(),
                    isTrialDay = false,
                    isGraceNight = false,
                    hadPriorDebt = false,
                    priorMonthChargedPaise = 0L,
                    ratePaisePerMin = 1_000L,
                    morningCapPaise = 30_000L,
                    monthlyCapPaise = 300_000L,
                ),
            )
            val octFirePoint = dev.prayush.obol.domain.time.TimePoint(octSched, 1L, 1_000L)
            val octEndPoint = dev.prayush.obol.domain.time.TimePoint(octSched.plusSeconds(900L), 1L, 901_000L)
            db1.obolDao().appendEvent("2026-10-15", octFirePoint, MorningEvent.AlarmFired)
            db1.obolDao().appendEvent("2026-10-15", octEndPoint, MorningEvent.BrushCompleted)
            db1.obolDao().appendEvent("2026-10-15", octEndPoint, MorningEvent.PaymentRecorded(Paise.fromRupees(100), "donorbox_oppia", "db-oct"))
            db1.obolDao().appendEvent("2026-10-15", octEndPoint, MorningEvent.MorningDismissed)

            // Add a November morning at current ₹20/min (₹140 on tab)
            val novAlarm = Instant.parse("2026-11-17T00:40:00Z")
            repo1.onAlarmFired(novAlarm)
            clock.advanceMinutes(12)
            repo1.appendActiveEvent(MorningEvent.BrushCompleted)
            repo1.appendActiveEvent(MorningEvent.MorningDismissed)
            assertTrue(repo1.declareGraceNightForNext())

            // 2. Link Drive JSON file & back up immediately
            val linkResult = manager1.linkAndBackup(driveUri)
            assertTrue(linkResult is BackupOpResult.Success)
            assertEquals(2, (linkResult as BackupOpResult.Success).morningCount)
            assertTrue(fakeIo.storage.containsKey(driveUri.toString()))

            val rawJson = fakeIo.readBytes(driveUri).toString(Charsets.UTF_8)
            assertTrue("Backup JSON should be human-readable and include schemaVersion", rawJson.contains("\"schemaVersion\": 1"))

            // 3. Reject invalid/corrupt backups cleanly
            assertTrue(ObolBackupCodec.decodeAndValidate("{}").isFailure)
            assertTrue(ObolBackupCodec.decodeAndValidate("{\"schemaVersion\": 99}").isFailure)
            assertTrue(ObolBackupCodec.decodeAndValidate("not-json").isFailure)

            // 4. Inspect & restore onto brand-new phone 2 (with unreadable Pixel ringtone URI)
            val preview = manager2.inspectBackupUri(driveUri).getOrThrow()
            assertEquals("obol-backup.json", preview.displayName)
            assertEquals(2, preview.morningCount)

            val restoredCount = manager2.restoreFromPreview(preview, resetPermissionsForNewPhone = false).getOrThrow()
            assertEquals(2, restoredCount)

            val restoredSnap = repo2.computeLiveSnapshot()
            assertEquals(2, restoredSnap.mornings.size)
            assertEquals("Historical ₹10/min rate must survive JSON backup/restore", Paise.fromRupees(100), restoredSnap.mornings[0].morningChargedPaise)
            assertEquals(Paise.fromRupees(140), restoredSnap.mornings[1].morningChargedPaise)
            assertEquals(Paise.fromRupees(140), restoredSnap.totalUnpaidPaise)
            assertEquals(LocalTime.of(5, 45), restoredSnap.alarmSlots.first { it.dayOfWeek == DayOfWeek.WEDNESDAY }.active.time)
            assertEquals(PaymentDestination.DonorboxOppia, restoredSnap.destinationState.active)
            assertTrue(restoredSnap.isNextAlarmGraceNight)
            assertEquals(
                "Unreadable Pixel ringtone URI on a new phone must fall back to Marimba",
                AlarmSoundChoice.BuiltIn(BuiltInChime.MARIMBA),
                restoredSnap.alarmSound,
            )

            // 5. SecurityException marks backup as needing reconnect without crashing
            fakeIo.failWriteWithSecurityException = true
            val failed = manager1.backupNow()
            assertTrue(failed is BackupOpResult.Failure && failed.needsReconnect)
            val errSnap = repo1.computeLiveSnapshot()
            assertNotNull(errSnap.lastBackupError)
            assertTrue(errSnap.backupDirty)
            fakeIo.failWriteWithSecurityException = false

            // 6. Locked Direct Boot defers write and flushes once unlocked
            val userManager = app.getSystemService(UserManager::class.java)
            shadowOf(userManager).setUserUnlocked(false)
            val deferred = manager1.backupNow()
            assertEquals(BackupOpResult.DeferredLocked, deferred)
            assertTrue(prefs1.current().backupDirty)

            shadowOf(userManager).setUserUnlocked(true)
            val flushed = manager1.flushIfDirty()
            assertTrue(flushed is BackupOpResult.Success)
            assertFalse(prefs1.current().backupDirty)
            assertNull(prefs1.current().lastBackupError)
        } finally {
            val userManager = app.getSystemService(UserManager::class.java)
            shadowOf(userManager).setUserUnlocked(true)
            db1.close()
            db2.close()
            scope.cancel()
            prefsFile1.delete()
            prefsFile2.delete()
        }
    }
}
