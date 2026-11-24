package dev.prayush.obol.payment

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.prayush.obol.design.ObolPalette
import dev.prayush.obol.design.ObolTypography
import dev.prayush.obol.design.RupeeSansText
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.domain.policy.PaymentDestinationState
import java.util.Locale

sealed interface UpiParseResult {
    data class Success(val txnRef: String) : UpiParseResult
    data class Submitted(val txnRef: String) : UpiParseResult
    data class Failed(val reason: String) : UpiParseResult
}

object PaymentController {

    const val DONORBOX_OPPIA_EMBED_URL =
        "https://donorbox.org/embed/oppia-annual-fundraising-campaign-2024"

    fun runAfterKeyguardDismissed(
        activity: Activity,
        onUnlocked: () -> Unit,
        onDismissFailed: (String) -> Unit,
    ) {
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        onUnlocked()
                    }

                    override fun onDismissCancelled() {
                        onDismissFailed("Unlock cancelled")
                    }

                    override fun onDismissError() {
                        onDismissFailed("Unlock error")
                    }
                },
            )
        } else {
            onUnlocked()
        }
    }

    fun formatUpiAmount(amountPaise: Paise): String {
        val whole = amountPaise.value / 100L
        val sub = amountPaise.value % 100L
        return "%d.%02d".format(Locale.US, whole, sub)
    }

    fun buildUpiPayUri(
        destination: PaymentDestination.Upi,
        amountPaise: Paise,
        txRef: String,
    ): Uri {
        require(ObolConstants.VPA_REGEX.matches(destination.vpa)) {
            "Invalid UPI VPA: ${destination.vpa}"
        }
        return Uri.Builder()
            .scheme("upi")
            .authority("pay")
            .appendQueryParameter("pa", destination.vpa)
            .appendQueryParameter("pn", destination.payeeName.ifBlank { "Charity" })
            .appendQueryParameter("tn", "Obol")
            .appendQueryParameter("cu", "INR")
            .appendQueryParameter("tr", txRef)
            .appendQueryParameter("am", formatUpiAmount(amountPaise))
            .build()
    }

    fun buildDonorboxUri(amountPaise: Paise): Uri {
        return Uri.parse(DONORBOX_OPPIA_EMBED_URL)
            .buildUpon()
            .appendQueryParameter("amount", amountPaise.rupees.toString())
            .appendQueryParameter("currency", "inr")
            .appendQueryParameter("default_interval", "o")
            .build()
    }

    fun launchDonorboxCustomTab(
        activity: Activity,
        amountPaise: Paise,
    ): Boolean {
        val uri = buildDonorboxUri(amountPaise)
        return try {
            val customTabs = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabs.launchUrl(activity, uri)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    fun parseUpiResult(
        resultCode: Int,
        data: Intent?,
        fallbackTimestampMs: Long = 0L,
    ): UpiParseResult {
        val rawResponse = data?.getStringExtra("response").orEmpty()
        val parsedMap = mutableMapOf<String, String>()
        if (rawResponse.isNotBlank()) {
            rawResponse.split("&").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = pair.substring(0, idx).trim().lowercase(Locale.US)
                    val value = pair.substring(idx + 1).trim()
                    parsedMap[key] = value
                }
            }
        }

        val statusRaw = (
            parsedMap["status"]
                ?: data?.getStringExtra("Status")
                ?: data?.getStringExtra("status")
                ?: ""
            ).trim().uppercase(Locale.US)

        val ref = parsedMap["approvalrefno"]
            ?.takeIf { it.isNotBlank() }
            ?: parsedMap["txnid"]?.takeIf { it.isNotBlank() }
            ?: data?.getStringExtra("txnId")?.takeIf { it.isNotBlank() }
            ?: "upi-$fallbackTimestampMs"

        return when {
            statusRaw == "SUCCESS" -> UpiParseResult.Success(ref)
            statusRaw == "SUBMITTED" || statusRaw == "PENDING" -> UpiParseResult.Submitted(ref)
            resultCode == Activity.RESULT_CANCELED && statusRaw.isEmpty() ->
                UpiParseResult.Failed("Cancelled")
            else -> UpiParseResult.Failed(statusRaw.ifBlank { "Payment did not complete" })
        }
    }
}

@Composable
fun DestinationSetupSheet(
    destinationState: PaymentDestinationState,
    onSaveUpi: (vpa: String, name: String) -> Unit,
    onSaveDonorbox: () -> Unit,
    onSendTenRupeeTestUpi: (vpa: String, name: String) -> Unit,
    onSendTenRupeeTestDonorbox: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = destinationState.active
    val initialVpa = (active as? PaymentDestination.Upi)?.vpa ?: ""
    val initialName = (active as? PaymentDestination.Upi)?.payeeName ?: "GiveIndia"

    var vpaText by remember(active) { mutableStateOf(initialVpa) }
    var nameText by remember(active) { mutableStateOf(initialName) }
    val isVpaValid = ObolConstants.VPA_REGEX.matches(vpaText.trim())
    val hasExistingActive = active != PaymentDestination.Unset

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("destination_sheet_scrim"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.70f))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(ObolPalette.Navy)
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(24.dp),
        ) {
            Text(
                text = "Money goes to",
                color = ObolPalette.Cream,
                style = ObolTypography.dryLineStyle(small = true),
            )
            Spacer(modifier = Modifier.height(6.dp))
            RupeeSansText(
                text = if (hasExistingActive) {
                    "Changes take 24 hours once set. Test anytime."
                } else {
                    "Pick one rail. Test anytime."
                },
                color = ObolPalette.Cream.copy(alpha = 0.62f),
                sizeSp = 13f,
                weight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Option 1: UPI VPA
            RupeeSansText(
                text = "UPI Charity VPA",
                color = ObolPalette.Cream,
                sizeSp = 15f,
                weight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SheetTextField(
                value = vpaText,
                placeholder = "charity@upi",
                onValueChange = { vpaText = it },
                tag = "upi_vpa_input",
            )
            Spacer(modifier = Modifier.height(8.dp))
            SheetTextField(
                value = nameText,
                placeholder = "Payee name",
                onValueChange = { nameText = it },
                tag = "upi_name_input",
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetButton(
                    text = if (hasExistingActive) "Use UPI (+24h)" else "Use UPI",
                    primary = true,
                    enabled = isVpaValid,
                    onClick = {
                        onSaveUpi(vpaText.trim(), nameText.trim().ifBlank { "Charity" })
                    },
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    text = "Send ₹10 test",
                    primary = false,
                    enabled = isVpaValid,
                    onClick = {
                        onSendTenRupeeTestUpi(vpaText.trim(), nameText.trim().ifBlank { "Charity" })
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Option 2: Oppia Donorbox
            RupeeSansText(
                text = "Oppia Foundation (Donorbox)",
                color = ObolPalette.Cream,
                sizeSp = 15f,
                weight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetButton(
                    text = if (hasExistingActive) "Use Oppia (+24h)" else "Use Oppia",
                    primary = true,
                    enabled = true,
                    onClick = onSaveDonorbox,
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    text = "Send ${ObolConstants.DONORBOX_TEST_PAYMENT_PAISE.formatRupees()} test",
                    primary = false,
                    enabled = true,
                    onClick = onSendTenRupeeTestDonorbox,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            SheetButton(
                text = "Close",
                primary = false,
                enabled = true,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun DonorboxReturnSheet(
    amountPaise: Paise,
    isTestOnly: Boolean,
    onConfirmedPaid: () -> Unit,
    onNotCompleted: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .testTag("donorbox_return_sheet"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(ObolPalette.Navy)
                .padding(24.dp),
        ) {
            Text(
                text = if (isTestOnly) {
                    "Did the ${amountPaise.formatRupees()} test go through?"
                } else {
                    "Did the donation complete?"
                },
                color = ObolPalette.Cream,
                style = ObolTypography.dryLineStyle(small = true),
            )
            Spacer(modifier = Modifier.height(16.dp))
            SheetButton(
                text = "Paid ${amountPaise.formatRupees()}",
                primary = true,
                enabled = true,
                onClick = onConfirmedPaid,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            SheetButton(
                text = "Not completed",
                primary = false,
                enabled = true,
                onClick = onNotCompleted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SheetTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    tag: String,
) {
    val style = ObolTypography.sansStyle(sizeSp = 15f, weight = FontWeight.Medium)
        .copy(color = ObolPalette.Cream)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ObolPalette.Ground)
            .border(1.dp, ObolPalette.Cream.copy(alpha = 0.24f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(tag),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = ObolPalette.Cream.copy(alpha = 0.38f),
                style = style,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(ObolPalette.Sun),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SheetButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    val bg = when {
        !enabled -> ObolPalette.Cream.copy(alpha = 0.10f)
        primary -> ObolPalette.Cream
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> ObolPalette.Cream.copy(alpha = 0.45f)
        primary -> ObolPalette.Navy
        else -> ObolPalette.Cream
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(bg)
            .let {
                if (!primary && enabled) {
                    it.border(1.5.dp, ObolPalette.Cream.copy(alpha = 0.38f), shape)
                } else {
                    it
                }
            }
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        RupeeSansText(
            text = text,
            color = fg,
            sizeSp = 15f,
            weight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
