package dev.prayush.obol.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.prayush.obol.domain.engine.MorningDerivation
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.MorningEventRecord
import dev.prayush.obol.domain.model.MorningHeader
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.policy.AlarmSlot
import dev.prayush.obol.domain.policy.AlarmSlotConfig
import dev.prayush.obol.domain.policy.PendingLock
import dev.prayush.obol.domain.time.TimePoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val ObolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

@Entity(tableName = "morning_headers")
data class MorningHeaderEntity(
    @PrimaryKey val morningId: String,
    val calendarDate: String,
    val scheduledAtEpochMs: Long,
    val isTrialDay: Boolean = false,
    val isGraceNight: Boolean,
    val hadPriorDebt: Boolean,
    val priorMonthChargedPaise: Long,
    @ColumnInfo(defaultValue = "2000") val ratePaisePerMin: Long = ObolConstants.RATE_PAISE_PER_MIN,
    @ColumnInfo(defaultValue = "60000") val morningCapPaise: Long = ObolConstants.MORNING_CAP_PAISE.value,
    @ColumnInfo(defaultValue = "600000") val monthlyCapPaise: Long = ObolConstants.MONTHLY_CAP_PAISE.value,
) {
    fun toDomain(): MorningHeader = MorningHeader(
        morningId = morningId,
        calendarDate = LocalDate.parse(calendarDate),
        scheduledAt = Instant.ofEpochMilli(scheduledAtEpochMs),
        isTrialDay = isTrialDay,
        isGraceNight = isGraceNight,
        hadPriorDebt = hadPriorDebt,
        priorMonthChargedPaise = Paise(priorMonthChargedPaise),
        ratePaisePerMin = ratePaisePerMin,
        morningCapPaise = Paise(morningCapPaise),
        monthlyCapPaise = Paise(monthlyCapPaise),
    )

    companion object {
        fun fromDomain(header: MorningHeader): MorningHeaderEntity = MorningHeaderEntity(
            morningId = header.morningId,
            calendarDate = header.calendarDate.toString(),
            scheduledAtEpochMs = header.scheduledAt.toEpochMilli(),
            isTrialDay = header.isTrialDay,
            isGraceNight = header.isGraceNight,
            hadPriorDebt = header.hadPriorDebt,
            priorMonthChargedPaise = header.priorMonthChargedPaise.value,
            ratePaisePerMin = header.ratePaisePerMin,
            morningCapPaise = header.morningCapPaise.value,
            monthlyCapPaise = header.monthlyCapPaise.value,
        )
    }
}

@Entity(
    tableName = "morning_events",
    foreignKeys = [
        ForeignKey(
            entity = MorningHeaderEntity::class,
            parentColumns = ["morningId"],
            childColumns = ["morningId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["morningId", "seq"], unique = true),
    ],
)
data class MorningEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val morningId: String,
    val seq: Int,
    val atEpochMs: Long,
    val bootCount: Long,
    val elapsedMs: Long,
    val payloadJson: String,
) {
    fun toDomain(): MorningEventRecord = MorningEventRecord(
        seq = seq,
        point = TimePoint(
            at = Instant.ofEpochMilli(atEpochMs),
            bootCount = bootCount,
            elapsedMs = elapsedMs,
        ),
        event = ObolJson.decodeFromString<MorningEvent>(payloadJson),
    )
}

data class MorningWithEvents(
    @Embedded val header: MorningHeaderEntity,
    @Relation(
        parentColumn = "morningId",
        entityColumn = "morningId",
    )
    val events: List<MorningEventEntity>,
) {
    fun toDomainEvents(): List<MorningEventRecord> =
        events.sortedBy { it.seq }.map { it.toDomain() }
}

@Entity(tableName = "alarm_slots")
data class AlarmSlotEntity(
    @PrimaryKey val dayOfWeek: Int,
    val activeEnabled: Boolean,
    val activeHour: Int,
    val activeMinute: Int,
    val pendingJson: String?,
) {
    fun toDomain(): AlarmSlot = AlarmSlot(
        dayOfWeek = DayOfWeek.of(dayOfWeek),
        active = AlarmSlotConfig(
            enabled = activeEnabled,
            time = LocalTime.of(activeHour, activeMinute),
        ),
        pending = pendingJson?.let { ObolJson.decodeFromString<PendingLock<AlarmSlotConfig>>(it) },
    )

    companion object {
        fun fromDomain(slot: AlarmSlot): AlarmSlotEntity = AlarmSlotEntity(
            dayOfWeek = slot.dayOfWeek.value,
            activeEnabled = slot.active.enabled,
            activeHour = slot.active.time.hour,
            activeMinute = slot.active.time.minute,
            pendingJson = slot.pending?.let { ObolJson.encodeToString(it) },
        )
    }
}

@Entity(tableName = "grace_nights")
data class GraceNightEntity(
    @PrimaryKey val scheduledAtEpochMs: Long,
    val calendarMonth: String,
    val declaredAtEpochMs: Long,
)

@Dao
interface ObolDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMorningHeader(header: MorningHeaderEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMorningEvent(event: MorningEventEntity): Long

    @Query("SELECT MAX(seq) FROM morning_events WHERE morningId = :morningId")
    suspend fun maxSeqForMorning(morningId: String): Int?

    @Transaction
    suspend fun appendEvent(
        morningId: String,
        point: TimePoint,
        event: MorningEvent,
    ): MorningEventEntity {
        val nextSeq = (maxSeqForMorning(morningId) ?: 0) + 1
        val entity = MorningEventEntity(
            morningId = morningId,
            seq = nextSeq,
            atEpochMs = point.at.toEpochMilli(),
            bootCount = point.bootCount,
            elapsedMs = point.elapsedMs,
            payloadJson = ObolJson.encodeToString<MorningEvent>(event),
        )
        insertMorningEvent(entity)
        return entity
    }

    @Transaction
    suspend fun createMorningWithInitialEvent(
        header: MorningHeaderEntity,
        point: TimePoint,
        initialEvent: MorningEvent,
    ): Boolean {
        val rowId = insertMorningHeader(header)
        if (rowId == -1L) return false
        appendEvent(header.morningId, point, initialEvent)
        return true
    }

    @Transaction
    @Query("SELECT * FROM morning_headers ORDER BY scheduledAtEpochMs ASC")
    fun observeAllMorningsWithEvents(): Flow<List<MorningWithEvents>>

    @Transaction
    @Query("SELECT * FROM morning_headers ORDER BY scheduledAtEpochMs ASC")
    suspend fun loadAllMorningsWithEvents(): List<MorningWithEvents>

    @Transaction
    @Query("SELECT * FROM morning_headers WHERE morningId = :morningId LIMIT 1")
    suspend fun loadMorningWithEvents(morningId: String): MorningWithEvents?

    @Transaction
    @Query("SELECT * FROM morning_headers WHERE calendarDate LIKE :monthPrefix || '%' ORDER BY scheduledAtEpochMs ASC")
    suspend fun loadMonthMornings(monthPrefix: String): List<MorningWithEvents>

    @Transaction
    suspend fun markPaidThroughRaw(
        expectedTotalPaise: Long,
        railId: String,
        ref: String,
        timePoint: TimePoint,
    ): Long {
        if (expectedTotalPaise <= 0L) return 0L
        val allMornings = loadAllMorningsWithEvents()
        var remaining = Paise(expectedTotalPaise)
        var allocatedTotal = Paise.ZERO

        for (morning in allMornings) {
            if (remaining.isZero) break
            val derived = MorningDerivation.evaluate(
                header = morning.header.toDomain(),
                events = morning.toDomainEvents(),
                evalPoint = timePoint,
            )
            val unpaid = derived.morningUnpaidPaise
            if (unpaid.isPositive) {
                val alloc = Paise.minOf(unpaid, remaining)
                appendEvent(
                    morningId = morning.header.morningId,
                    point = timePoint,
                    event = MorningEvent.PaymentRecorded(
                        amountPaise = alloc,
                        railId = railId,
                        ref = ref,
                    ),
                )
                remaining = remaining.minusAtLeastZero(alloc)
                allocatedTotal += alloc
            }
        }
        return allocatedTotal.value
    }

    @Query("SELECT * FROM alarm_slots ORDER BY dayOfWeek ASC")
    fun observeAlarmSlots(): Flow<List<AlarmSlotEntity>>

    @Query("SELECT * FROM alarm_slots ORDER BY dayOfWeek ASC")
    suspend fun loadAlarmSlots(): List<AlarmSlotEntity>

    @Upsert
    suspend fun upsertAlarmSlots(slots: List<AlarmSlotEntity>)

    @Upsert
    suspend fun upsertAlarmSlot(slot: AlarmSlotEntity)

    @Query("SELECT * FROM grace_nights ORDER BY scheduledAtEpochMs ASC")
    fun observeGraceNights(): Flow<List<GraceNightEntity>>

    @Query("SELECT * FROM grace_nights ORDER BY scheduledAtEpochMs ASC")
    suspend fun loadGraceNights(): List<GraceNightEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGraceNight(entity: GraceNightEntity): Long

    @Query("DELETE FROM grace_nights WHERE scheduledAtEpochMs = :scheduledAtEpochMs")
    suspend fun deleteGraceNight(scheduledAtEpochMs: Long): Int

    @Query("DELETE FROM morning_events")
    suspend fun deleteAllMorningEvents()

    @Query("DELETE FROM morning_headers")
    suspend fun deleteAllMorningHeaders()

    @Query("DELETE FROM alarm_slots")
    suspend fun deleteAllAlarmSlots()

    @Query("DELETE FROM grace_nights")
    suspend fun deleteAllGraceNights()

    @Transaction
    suspend fun replaceAllData(
        headers: List<MorningHeaderEntity>,
        events: List<MorningEventEntity>,
        slots: List<AlarmSlotEntity>,
        graceNights: List<GraceNightEntity>,
    ) {
        deleteAllMorningEvents()
        deleteAllMorningHeaders()
        deleteAllAlarmSlots()
        deleteAllGraceNights()
        for (header in headers) {
            insertMorningHeader(header)
        }
        for (event in events) {
            insertMorningEvent(event)
        }
        if (slots.isNotEmpty()) {
            upsertAlarmSlots(slots)
        }
        for (grace in graceNights) {
            insertGraceNight(grace)
        }
    }
}

suspend fun ObolDao.markPaidThrough(
    expectedTotalPaise: Paise,
    railId: String,
    ref: String,
    timePoint: TimePoint,
): Paise = Paise(markPaidThroughRaw(expectedTotalPaise.value, railId, ref, timePoint))

@Database(
    entities = [
        MorningHeaderEntity::class,
        MorningEventEntity::class,
        AlarmSlotEntity::class,
        GraceNightEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ObolDatabase : RoomDatabase() {
    abstract fun obolDao(): ObolDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE morning_headers ADD COLUMN ratePaisePerMin INTEGER NOT NULL DEFAULT 2000")
                db.execSQL("ALTER TABLE morning_headers ADD COLUMN morningCapPaise INTEGER NOT NULL DEFAULT 60000")
                db.execSQL("ALTER TABLE morning_headers ADD COLUMN monthlyCapPaise INTEGER NOT NULL DEFAULT 600000")
            }
        }

        fun create(context: Context, inMemory: Boolean = false): ObolDatabase {
            val dpContext = if (context.isDeviceProtectedStorage) {
                context
            } else {
                context.createDeviceProtectedStorageContext()
            }
            return if (inMemory) {
                Room.inMemoryDatabaseBuilder(dpContext, ObolDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            } else {
                Room.databaseBuilder(dpContext, ObolDatabase::class.java, "obol.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
            }
        }
    }
}
