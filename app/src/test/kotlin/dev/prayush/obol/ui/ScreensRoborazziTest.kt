package dev.prayush.obol.ui

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.prayush.obol.domain.policy.AlarmSchedulePolicy
import dev.prayush.obol.domain.policy.AlarmSlotConfig
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.BuiltInChime
import dev.prayush.obol.domain.time.FakeObolClock
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ScreensRoborazziTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                val activityInfo = ActivityInfo().apply {
                    name = ComponentActivity::class.java.name
                    packageName = app.packageName
                }
                shadowOf(app.packageManager).addOrUpdateActivity(activityInfo)
            }
        },
    ).around(composeRule)

    @Test
    fun renderAllActiveScreensAndAlarmSoundPickerAndVerifyBoundsAndCopyParity() {
        val presets = ObolCopy.presetScreens()
        assertEquals("Expected exact 25 canonical active screens (trial week removed)", 25, presets.size)
        assertTrue("13-trial screen must not exist in presets", presets.none { it.screenId == "13-trial" })

        val outDir = File("roborazzi")
        outDir.mkdirs()

        val trackedTags = listOf(
            "status_row",
            "quiet_104.0",
            "hero_text",
            "sub_hero_quiet",
            "cap_subtext",
            "dry_line",
            "closed_subtitle",
            "quiet_384.0",
            "quiet_388.0",
            "rows_container",
            "camera_status_line",
            "primary_pill",
            "ghost_pill",
            "secondary_pill",
            "bottom_note",
        )

        var currentPreset by mutableStateOf(presets.first())
        var showSoundPicker by mutableStateOf(false)
        var showBackupSheet by mutableStateOf(false)
        var soundSheetSavedSystem by mutableStateOf<AlarmSoundChoice.SystemAlarm?>(null)
        var undoGraceClicks = 0
        composeRule.setContent {
            Box(modifier = Modifier.size(width = 360.dp, height = 800.dp)) {
                ObolScreenRenderer(
                    model = currentPreset,
                    callbacks = ObolScreenCallbacks(
                        onUndoGraceNight = { undoGraceClicks += 1 },
                    ),
                    isStaticPreview = true,
                )
                if (showSoundPicker) {
                    AlarmSoundSheet(
                        selectedChoice = AlarmSoundChoice.BuiltIn(BuiltInChime.SINGING_BOWL),
                        previewingChoice = AlarmSoundChoice.BuiltIn(BuiltInChime.SINGING_BOWL),
                        onSelectAndPreviewBuiltIn = {},
                        onToggleSystemPreview = {},
                        onLaunchSystemPicker = {},
                        onDismiss = {},
                        systemAlarmOption = soundSheetSavedSystem,
                    )
                }
                if (showBackupSheet) {
                    BackupSheet(
                        backupUri = "content://com.google.android.apps.docs.storage/document/obol-backup.json",
                        backupDisplayName = "obol-backup.json",
                        lastBackupAt = Instant.parse("2026-11-24T14:00:00Z"),
                        lastBackupError = null,
                        isSyncing = false,
                        morningCount = 12,
                        zone = ZoneId.of("Asia/Kolkata"),
                        feedbackMessage = null,
                        pendingRestorePreview = null,
                        onChooseBackupFile = {},
                        onBackupNow = {},
                        onPickRestoreFile = {},
                        onConfirmRestore = {},
                        onCancelRestore = {},
                        onDisconnect = {},
                        onDismiss = {},
                    )
                }
            }
        }

        for (preset in presets) {
            composeRule.runOnUiThread {
                showSoundPicker = false
                currentPreset = preset
            }
            composeRule.waitForIdle()

            val outFile = File(outDir, "${preset.screenId}.png")
            composeRule.onRoot().captureRoboImage(outFile)
            assertTrue("Expected PNG written for ${preset.screenId}", outFile.exists() && outFile.length() > 1_000L)

            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            val foundRects = mutableListOf<Pair<String, Rect>>()

            for (tag in trackedTags) {
                val nodes = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                if (nodes.isNotEmpty()) {
                    val b = nodes.first().boundsInRoot
                    assertTrue(
                        "Clipped horizontally on ${preset.screenId} tag=$tag bounds=$b root=$rootBounds",
                        b.left >= -2f && b.right <= rootBounds.width + 2f,
                    )
                    assertTrue(
                        "Clipped vertically on ${preset.screenId} tag=$tag bounds=$b root=$rootBounds",
                        b.top >= -2f && b.bottom <= rootBounds.height + 2f,
                    )
                    foundRects.add(tag to b)
                }
            }

            val sortedRects = foundRects.sortedBy { it.second.top }
            for (i in 0 until sortedRects.size - 1) {
                val (tagA, rectA) = sortedRects[i]
                val (tagB, rectB) = sortedRects[i + 1]
                val tolerancePx = if (tagA == "quiet_104.0" && tagB == "hero_text") 24f else 4f
                assertTrue(
                    "Vertical overlap on ${preset.screenId} between $tagA ($rectA) and $tagB ($rectB)",
                    rectA.bottom <= rectB.top + tolerancePx,
                )
            }

            if (preset.screenId in setOf("04-home", "22-closed")) {
                val histBounds = composeRule.onAllNodesWithTag("nav_history", useUnmergedTree = true)
                    .fetchSemanticsNodes().first().boundsInRoot
                val setBounds = composeRule.onAllNodesWithTag("nav_settings", useUnmergedTree = true)
                    .fetchSemanticsNodes().first().boundsInRoot
                assertTrue(
                    "Expected symmetric spacing between History and Settings on ${preset.screenId}: hist=$histBounds settings=$setBounds",
                    setBounds.left - histBounds.right >= 12f,
                )
            }

            if (preset.screenId in setOf(
                    "01-welcome",
                    "04-home",
                    "07-home-paused",
                    "08-alarm-free",
                    "10-snoozed",
                    "14-brushing",
                    "22-closed",
                    "23-closed-free",
                    "25-history",
                    "26-bill",
                )
            ) {
                val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
                val bg = bmp.getPixel(4, 240)
                val scanRightMax = if (preset.screenId in setOf("01-welcome", "04-home", "07-home-paused")) 580 else (bmp.width - 1)
                var rightmostX = -1
                var rightmostInkCount = 0
                for (x in scanRightMax downTo 40) {
                    var count = 0
                    for (y in 250 until 510) {
                        val px = bmp.getPixel(x, y)
                        val dist = kotlin.math.abs(AndroidColor.red(px) - AndroidColor.red(bg)) +
                            kotlin.math.abs(AndroidColor.green(px) - AndroidColor.green(bg)) +
                            kotlin.math.abs(AndroidColor.blue(px) - AndroidColor.blue(bg))
                        if (dist > 80) count += 1
                    }
                    if (count > 0) {
                        rightmostX = x
                        rightmostInkCount = count
                        break
                    }
                }
                assertTrue("Expected hero glyph ink on ${preset.screenId}", rightmostX > 150)
                assertTrue(
                    "Hero italic glyph appears vertically sliced on ${preset.screenId} at x=$rightmostX (edgeCount=$rightmostInkCount)",
                    rightmostInkCount <= 32,
                )
            }

            if (preset.screenId == "03-alarms") {
                composeRule.onNodeWithText("Changes take effect right away.").assertExists()
                val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
                val bg = bmp.getPixel(4, 420)
                var timeRightX = -1
                var timeRightInkCount = 0
                for (x in 565 downTo 400) {
                    var count = 0
                    for (y in 395 until 460) {
                        val px = bmp.getPixel(x, y)
                        val dist = kotlin.math.abs(AndroidColor.red(px) - AndroidColor.red(bg)) +
                            kotlin.math.abs(AndroidColor.green(px) - AndroidColor.green(bg)) +
                            kotlin.math.abs(AndroidColor.blue(px) - AndroidColor.blue(bg))
                        if (dist > 80) count += 1
                    }
                    if (count > 0) {
                        timeRightX = x
                        timeRightInkCount = count
                        break
                    }
                }
                assertTrue(
                    "Expected 6:10 alarm time right edge near x=544 (18dp before toggle at x=580), got x=$timeRightX",
                    timeRightX in 536..552,
                )
                assertTrue(
                    "Expected smooth unclipped right curve on 6:10 in 03-alarms, got edgeCount=$timeRightInkCount",
                    timeRightInkCount <= 16,
                )
            }

            when (preset.screenId) {
                "01-welcome" -> {
                    composeRule.onNodeWithText("Set me up").assertExists()
                    composeRule.onNodeWithText("5 free minutes after the alarm.\nThen ₹20 a minute.\nTen seconds of brushing stops it.").assertExists()
                    composeRule.onNodeWithText("Up to ₹600 a morning. Three nights off a month.").assertExists()
                    composeRule.onNodeWithText("Restore from backup").assertExists()
                    composeRule.onNodeWithText("The first week is a trial. Nothing's charged.").assertDoesNotExist()
                }
                "06-home-night-off" -> {
                    composeRule.onNodeWithText("Undo night off").assertExists()
                    composeRule.onNodeWithText("1 left").assertExists()
                    composeRule.onNodeWithTag("secondary_pill").performClick()
                    assertEquals(1, undoGraceClicks)
                }
                "07-home-paused" -> {
                    composeRule.onNodeWithText("Pausing and resuming are instant.").assertExists()
                }
                "08-alarm-free" -> composeRule.onNodeWithText("Quieter. Not cheaper.").assertExists()
                "12-capped" -> composeRule.onNodeWithText("Charging has stopped. Ringing has not.").assertExists()
                "14-brushing" -> {
                    composeRule.onNodeWithText("0:06").assertExists()
                    composeRule.onNodeWithText("Ten seconds. Don't tell\nyour dentist.").assertExists()
                }
                "16-override" -> {
                    composeRule.onNodeWithText("Stop the meter for ₹20").assertExists()
                    composeRule.onNodeWithText("₹20 adds to this morning's bill.").assertExists()
                }
                "26-bill" -> {
                    composeRule.onNodeWithText("Every rupee, itemised.").assertExists()
                    composeRule.onNodeWithText("Ten seconds done. Meter off").assertExists()
                }
                "27-settings" -> {
                    composeRule.onNodeWithText("₹20 a minute").assertExists()
                    composeRule.onNodeWithText("₹600").assertExists()
                    composeRule.onNodeWithText("₹6,000").assertExists()
                    composeRule.onNodeWithText("₹300 tab").assertExists()
                    composeRule.onNodeWithText("10 seconds").assertExists()
                    composeRule.onNodeWithText("Alarm sound").assertExists()
                    composeRule.onNodeWithText("Marimba").assertExists()
                    composeRule.onNodeWithText("Google Drive backup").assertExists()
                    composeRule.onNodeWithText("Pausing stops all alarms immediately.").assertExists()
                    composeRule.onNodeWithText("Trial week").assertDoesNotExist()
                }
            }
        }

        // Render and verify AlarmSoundSheet overlay over 27-settings
        composeRule.runOnUiThread {
            currentPreset = presets.last()
            showSoundPicker = true
        }
        composeRule.waitForIdle()
        val sheetOutFile = File(outDir, "27b-alarm-sound-sheet.png")
        composeRule.onRoot().captureRoboImage(sheetOutFile)
        assertTrue(sheetOutFile.exists() && sheetOutFile.length() > 1_000L)
        composeRule.onNodeWithTag("sound_option_marimba").assertExists()
        composeRule.onNodeWithTag("sound_option_singing_bowl").assertExists()
        composeRule.onNodeWithTag("sound_option_electric_piano").assertExists()
        composeRule.onNodeWithText("Playing · Stop").assertExists()
        composeRule.onNodeWithTag("pick_pixel_alarm_button").assertExists()
        composeRule.onNodeWithTag("alarm_sound_done_button").assertExists()

        // Verify previously picked Pixel system alarm card stays selectable while previewing a built-in chime
        composeRule.runOnUiThread {
            soundSheetSavedSystem = AlarmSoundChoice.SystemAlarm("content://media/internal/audio/media/42", "Bright Morning")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sound_option_system_active").assertExists()
        composeRule.onNodeWithText("Bright Morning").assertExists()
        composeRule.onNodeWithText("Change Pixel alarm sound…").assertExists()

        composeRule.runOnUiThread {
            soundSheetSavedSystem = null
            showSoundPicker = false
            showBackupSheet = true
        }
        composeRule.waitForIdle()
        val backupSheetOutFile = File(outDir, "27c-backup-sheet.png")
        composeRule.onRoot().captureRoboImage(backupSheetOutFile)
        assertTrue(backupSheetOutFile.exists() && backupSheetOutFile.length() > 1_000L)
        composeRule.onNodeWithTag("backup_sheet_scrim").assertExists()
        composeRule.onNodeWithText("obol-backup.json").assertExists()
        composeRule.onNodeWithTag("backup_now_button").assertExists()
        composeRule.onNodeWithTag("backup_restore_button").assertExists()

        composeRule.runOnUiThread {
            showBackupSheet = false
        }

        // Verify onboarding 03-alarms shows "Done" and never "Start trial week"
        val onboardingAlarms = (presets.first { it.screenId == "03-alarms" } as ObolScreenModel.Alarms)
            .copy(isOnboarding = true)
        composeRule.runOnUiThread { currentPreset = onboardingAlarms }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Done").assertExists()
        composeRule.onNodeWithText("Start trial week").assertDoesNotExist()

        // Verify instant alarm edits leave zero pending lock caption
        val zone = ZoneId.of("Asia/Kolkata")
        val sunNight = Instant.parse("2026-11-22T16:23:00Z") // Sun 21:53 IST
        val clock = FakeObolClock(initialInstant = sunNight, initialZone = zone)
        val monSlot = AlarmSchedulePolicy.defaultSlots().first { it.dayOfWeek == DayOfWeek.MONDAY }
        val editedMon = AlarmSchedulePolicy.applyEdit(
            slot = monSlot,
            desired = AlarmSlotConfig(enabled = true, time = LocalTime.of(7, 30)),
            point = clock.timePoint(),
            isOnboarding = false,
            zone = zone,
        )
        val nightRows = ObolCopy.mapAlarmRows(listOf(editedMon), clock.now(), zone)
        assertEquals("7:30", nightRows.single().timeText)
        assertEquals(null, nightRows.single().pendingCaption)

        // Verify All-time History view renders cleanly with month dates, all-time total, and "This month" toggle
        val allTimeHistory = ObolScreenModel.HistoryList(
            screenId = "25b-history-all-time",
            monthTitle = "All time",
            monthTotalText = "₹1,420",
            capSubtext = "18 mornings · 6 paid",
            dryLine = "Avg 11 min · worst 18 min.",
            rows = listOf(
                HistoryRowUi("2026-11-24", "24 Nov", "up in 12 min", "₹140", false),
                HistoryRowUi("2026-11-20", "20 Nov", "up in 3 min", "free", true),
                HistoryRowUi("2026-10-28", "28 Oct", "up in 18 min", "₹260", false),
                HistoryRowUi("2026-10-15", "15 Oct", "night off", "free", true),
            ),
            hasEarlier = false,
            showingAllTime = true,
        )
        composeRule.runOnUiThread { currentPreset = allTimeHistory }
        composeRule.waitForIdle()
        val allTimeOutFile = File(outDir, "25b-history-all-time.png")
        composeRule.onRoot().captureRoboImage(allTimeOutFile)
        assertTrue(allTimeOutFile.exists() && allTimeOutFile.length() > 1_000L)
        composeRule.onNodeWithText("All time").assertExists()
        composeRule.onNodeWithText("18 mornings · 6 paid").assertExists()
        composeRule.onNodeWithText("Avg 11 min · worst 18 min.").assertExists()
        composeRule.onNodeWithText("24 Nov").assertExists()
        composeRule.onNodeWithText("28 Oct").assertExists()
        composeRule.onNodeWithText("This month").assertExists()

        // Verify 15-row History + Earlier link never overlap and 15-row FullBill never clips past 800dp
        val crowdedHistory = ObolScreenModel.HistoryList(
            monthTitle = "November",
            monthTotalText = "₹900",
            rows = (1..15).map { idx ->
                HistoryRowUi("2026-11-$idx", "Day $idx", "up in 10 min", "₹60", false)
            },
            hasEarlier = true,
        )
        composeRule.runOnUiThread { currentPreset = crowdedHistory }
        composeRule.waitForIdle()
        val histRowsRect = composeRule.onAllNodesWithTag("rows_container", useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        val earlierRect = composeRule.onAllNodesWithTag("earlier_link", useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        assertTrue(
            "Scrollable history container ($histRowsRect) must stay above Earlier link ($earlierRect)",
            histRowsRect.bottom <= earlierRect.top,
        )

        val crowdedBill = ObolScreenModel.FullBill(
            backLabel = "‹ November",
            dateLabel = "Wed 18 Nov",
            heroTotal = "₹300",
            rows = (1..15).map { idx -> BillItemUi("6:${10 + idx}", "Event $idx", "₹${idx * 10}") },
        )
        composeRule.runOnUiThread { currentPreset = crowdedBill }
        composeRule.waitForIdle()
        val billRootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val billRowsRect = composeRule.onAllNodesWithTag("rows_container", useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        assertTrue(
            "Scrollable bill container ($billRowsRect) must fit inside 800dp root ($billRootBounds)",
            billRowsRect.bottom <= billRootBounds.height,
        )

        // Verify Home running-tab state (< ₹300) renders cleanly with no Pay button
        val homeTabPreset = ObolScreenModel.Home(
            screenId = "05b-home-tab",
            statusLeft = "Tue 22:47",
            label = "Tomorrow, Wednesday",
            heroText = "6:10",
            dryLine = "Sleep well. I'm rooting\nfor the snooze button.",
            subtitle = null,
            unpaidAmountText = "₹140",
            unpaidSuffix = " on tab",
            unpaidNote = "Pays once your tab hits ₹300.\nTomorrow's free minutes are safe.",
            primaryButton = null,
            nightOffText = "Take a night off",
            nightOffTrailing = "3 left",
            bottomNote = "Change alarms",
            bottomNoteIsLink = true,
        )
        composeRule.runOnUiThread { currentPreset = homeTabPreset }
        composeRule.waitForIdle()
        val tabOutFile = File(outDir, "05b-home-tab.png")
        composeRule.onRoot().captureRoboImage(tabOutFile)
        assertTrue(tabOutFile.exists() && tabOutFile.length() > 1_000L)
        composeRule.onNodeWithText("₹140").assertExists()
        composeRule.onNodeWithText(" on tab").assertExists()
        composeRule.onNodeWithText("Pays once your tab hits ₹300.\nTomorrow's free minutes are safe.").assertExists()
        composeRule.onNodeWithTag("primary_pill").assertDoesNotExist()
    }
}
