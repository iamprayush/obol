package dev.prayush.obol.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.prayush.obol.design.ObolPalette
import dev.prayush.obol.ui.ObolCopy
import dev.prayush.obol.ui.ObolViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugDrawerOverlay(
    viewModel: ObolViewModel? = null,
) {
    if (viewModel == null) return

    var isOpen by remember { mutableStateOf(false) }
    val previewScreen by viewModel.debugPreviewScreen.collectAsStateWithLifecycle()
    val snapshot by viewModel.container.repository.snapshot.collectAsStateWithLifecycle()
    val presets = remember { ObolCopy.presetScreens() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Small unobtrusive DBG pill at top center
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(ObolPalette.Ground.copy(alpha = 0.75f))
                .border(1.dp, ObolPalette.Sun.copy(alpha = 0.55f), RoundedCornerShape(99.dp))
                .clickable { isOpen = !isOpen }
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .testTag("debug_drawer_toggle"),
        ) {
            Text(
                text = if (previewScreen != null) "DBG · ${previewScreen!!.screenId}" else "DBG",
                color = ObolPalette.Sun,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (isOpen) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .clickable { isOpen = false },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(ObolPalette.Navy)
                        .pointerInput(Unit) { detectTapGestures {} }
                        .padding(18.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Obol Debug Drawer",
                            color = ObolPalette.Cream,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        DbgPill("Close", hot = false) { isOpen = false }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Clock & Reboot",
                        color = ObolPalette.Cream.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DbgPill("+1m") { viewModel.debugAdvanceClockMs(60_000L) }
                        DbgPill("+5m") { viewModel.debugAdvanceClockMs(5 * 60_000L) }
                        DbgPill("+20m") { viewModel.debugAdvanceClockMs(20 * 60_000L) }
                        DbgPill("+60m") { viewModel.debugAdvanceClockMs(60 * 60_000L) }
                        DbgPill("+12h") { viewModel.debugAdvanceClockMs(12 * 3_600_000L) }
                        DbgPill("+24h") { viewModel.debugAdvanceClockMs(24 * 3_600_000L) }
                        DbgPill("Reboot +2m") { viewModel.debugSimulateReboot(2 * 60_000L) }
                        DbgPill("Reset clock") { viewModel.debugResetClockOffsets() }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Morning & Vision Simulation",
                        color = ObolPalette.Cream.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DbgPill("Skip setup") {
                            viewModel.setDebugPreviewScreen(null)
                            viewModel.debugSkipOnboarding()
                        }
                        DbgPill("Fire alarm", hot = true) {
                            viewModel.setDebugPreviewScreen(null)
                            viewModel.debugTriggerAlarmNow(graceNight = false)
                            isOpen = false
                        }
                        DbgPill("Fire night off") {
                            viewModel.setDebugPreviewScreen(null)
                            viewModel.debugTriggerAlarmNow(graceNight = true)
                            isOpen = false
                        }
                        DbgPill("Brush seen") { viewModel.debugSimulateBrushingSeenStep() }
                        DbgPill("Finish 10s brush", hot = true) {
                            viewModel.debugSimulateBrushingPassComplete()
                            isOpen = false
                        }
                        DbgPill("Camera error") { viewModel.onCameraHardwareError() }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Payment Simulation",
                        color = ObolPalette.Cream.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val payable = snapshot.activeMorning?.totalPayableNowPaise ?: snapshot.totalUnpaidPaise
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DbgPill("Pay SUCCESS (${payable.formatRupees()})") {
                            viewModel.onUpiPaymentCompleted(payable, "dbg-ok")
                            isOpen = false
                        }
                        DbgPill("Pay SUBMITTED") {
                            viewModel.onUpiPaymentSubmitted(payable, "dbg-sub")
                            isOpen = false
                        }
                        DbgPill("Pay FAILED") {
                            viewModel.onPaymentFailed("Simulated failure")
                            isOpen = false
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Screen Gallery",
                        color = ObolPalette.Cream.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DbgPill(
                            text = "LIVE",
                            hot = previewScreen == null,
                            onClick = {
                                viewModel.setDebugPreviewScreen(null)
                                isOpen = false
                            },
                        )
                        presets.forEach { preset ->
                            DbgPill(
                                text = preset.screenId,
                                hot = previewScreen?.screenId == preset.screenId,
                                onClick = {
                                    viewModel.setDebugPreviewScreen(preset)
                                    isOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DbgPill(
    text: String,
    hot: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (hot) ObolPalette.Sun else ObolPalette.Ground)
            .border(1.dp, ObolPalette.Cream.copy(alpha = 0.25f), RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (hot) ObolPalette.Navy else ObolPalette.Cream,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
