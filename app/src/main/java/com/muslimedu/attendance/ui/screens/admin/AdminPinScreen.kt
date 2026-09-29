package com.muslimedu.attendance.ui.screens.admin

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muslimedu.attendance.security.AdminPinManager
import com.muslimedu.attendance.ui.theme.AccentRed
import com.muslimedu.attendance.ui.theme.BrandPrimary
import com.muslimedu.attendance.ui.theme.BrandTeal
import com.muslimedu.attendance.viewmodel.AdminPinViewModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_DIGITS = 8

/**
 * Gate for the admin screens: an access-code keypad (dots + round number
 * keys, no system keyboard). Creates a PIN on first use (or when
 * [forceCreate] - changing it, or after a reset) by entering it twice;
 * otherwise asks for it and checks it as soon as the last digit is in.
 * "Forgot PIN" hands off to an admin login, which is the only way to reset
 * it short of clearing app data (which would also lose unsynced scans).
 */
@Composable
fun AdminPinScreen(
    onUnlocked: () -> Unit,
    onForgotPin: () -> Unit,
    forceCreate: Boolean = false,
    requestId: Long = 0L,
    viewModel: AdminPinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    var entered by remember(requestId) { mutableStateOf("") }
    // Creating: the first entry, kept until it's typed again to confirm.
    var firstEntry by remember(requestId) { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(1f) }

    LaunchedEffect(requestId, forceCreate) { viewModel.reset(forceCreate) }
    LaunchedEffect(state.unlocked) {
        if (state.unlocked) {
            // Consumed immediately: this ViewModel outlives the screen, and a
            // stale `unlocked` would otherwise let the next visit skip the PIN.
            viewModel.consumeUnlocked()
            onUnlocked()
        }
    }
    // Any error: shake the dots and start the entry again.
    LaunchedEffect(state.errorCount) {
        if (state.errorCount == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        entered = ""
        firstEntry = null
        shake.snapTo(0f)
        shake.animateTo(1f, tween(450))
    }

    val length = state.pinLength
    fun submit(code: String) {
        when {
            !state.isCreating -> viewModel.unlock(code)
            firstEntry == null -> {
                firstEntry = code
                entered = ""
            }
            code == firstEntry -> viewModel.create(code, code)
            else -> viewModel.confirmMismatch()
        }
    }
    // A known length is checked by itself once the last dot fills (after a beat, so it shows filled).
    LaunchedEffect(entered) {
        if (length != null && entered.length == length && !state.isBusy) {
            delay(150)
            submit(entered)
        }
    }

    val title = when {
        !state.isCreating -> "Enter the access code"
        firstEntry == null -> "Create a ${AdminPinManager.NEW_PIN_LENGTH}-digit access code"
        else -> "Confirm the access code"
    }
    val subtitle = when {
        !state.isCreating -> "Admin screens are locked on this device."
        firstEntry == null -> "Needed for card & face registration, the gate schedule and sync. Gate scanning stays open to everyone."
        else -> "Enter the same ${AdminPinManager.NEW_PIN_LENGTH} digits again."
    }
    val canType = !state.isBusy

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(
                Brush.verticalGradient(
                    0f to BrandTeal.copy(alpha = 0.16f),
                    0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                ),
            ),
    ) {
        // A phone on its side (kiosk mode forces landscape) has well under half
        // the height the stacked layout needs, so the keypad goes beside the
        // title there; either way the keys shrink until all four rows fit.
        val sideBySide = maxWidth > maxHeight && maxHeight < SIDE_BY_SIDE_BELOW
        val rowGap = if (sideBySide) 10.dp else 16.dp
        val columnGap = if (sideBySide) 20.dp else 28.dp
        val keySize = if (sideBySide) {
            fitKeySize(height = maxHeight - 16.dp, width = maxWidth / 2 - 24.dp, rowGap = rowGap, columnGap = columnGap)
        } else {
            fitKeySize(height = maxHeight - STACKED_RESERVED, width = maxWidth - 48.dp, rowGap = rowGap, columnGap = columnGap)
        }

        val header: @Composable () -> Unit = {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Shakes on a wrong code: a decaying sine over the animation.
            val dx = if (shake.value < 1f) (sin(shake.value * PI * 6) * (1f - shake.value) * 16).toFloat() else 0f
            PinDots(
                count = length ?: maxOf(AdminPinManager.NEW_PIN_LENGTH, entered.length),
                filled = entered.length,
                error = state.error != null && entered.isEmpty(),
                modifier = Modifier
                    .padding(top = 28.dp)
                    .offset { IntOffset(dx.dp.roundToPx(), 0) },
            )
            Box(modifier = Modifier.height(48.dp).padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
                when {
                    state.isBusy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BrandPrimary)
                    state.error != null -> Text(
                        state.error.orEmpty(),
                        color = AccentRed,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        val keypad: @Composable () -> Unit = {
            Keypad(
                keySize = keySize,
                rowGap = rowGap,
                columnGap = columnGap,
                enabled = canType,
                // An older PIN of unknown length is checked with this key instead.
                showOk = length == null && entered.length >= AdminPinManager.NEW_PIN_LENGTH,
                onDigit = { digit ->
                    if (entered.length < (length ?: MAX_DIGITS)) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        entered += digit
                    }
                },
                onDelete = {
                    if (entered.isNotEmpty()) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        entered = entered.dropLast(1)
                    }
                },
                onOk = { submit(entered) },
            )
        }

        val forgot: @Composable () -> Unit = {
            Box(modifier = Modifier.heightIn(min = 56.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                if (!state.isCreating) {
                    TextButton(onClick = onForgotPin) {
                        Text("Forgot the code? Reset with an admin login", textAlign = TextAlign.Center)
                    }
                }
            }
        }

        if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    header()
                    forgot()
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { keypad() }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(0.6f))
                header()
                Spacer(Modifier.weight(1f))
                keypad()
                forgot()
            }
        }
    }
}

/** Below this height a wider-than-tall screen gets the keypad beside the title. */
private val SIDE_BY_SIDE_BELOW = 600.dp

/** Height the stacked layout needs for everything but the keypad (title, dots, status, forgot link, padding). */
private val STACKED_RESERVED = 270.dp

private val KEY_SIZE = 76.dp
private val MIN_KEY_SIZE = 44.dp

/** The largest key, up to [KEY_SIZE], whose 4 rows x 3 columns fit [height] x [width]. */
private fun fitKeySize(height: Dp, width: Dp, rowGap: Dp, columnGap: Dp): Dp {
    val byHeight = (height - rowGap * 3) / 4
    val byWidth = (width - columnGap * 2) / 3
    return maxOf(MIN_KEY_SIZE, minOf(KEY_SIZE, byHeight, byWidth))
}

@Composable
private fun PinDots(count: Int, filled: Int, error: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        repeat(count) { i ->
            val color by animateColorAsState(
                when {
                    error -> AccentRed.copy(alpha = 0.6f)
                    i < filled -> BrandPrimary
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                label = "dot",
            )
            Box(modifier = Modifier.size(16.dp).background(color, CircleShape))
        }
    }
}

/** 1-9, then [OK when needed] 0 [delete] - round keys like a phone lock screen. */
@Composable
private fun Keypad(
    keySize: Dp,
    rowGap: Dp,
    columnGap: Dp,
    enabled: Boolean,
    showOk: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onOk: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(rowGap), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(columnGap)) {
                row.forEach { digit -> DigitKey(digit, keySize, enabled) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(columnGap), verticalAlignment = Alignment.CenterVertically) {
            if (showOk) {
                Surface(onClick = onOk, enabled = enabled, shape = CircleShape, color = BrandPrimary, modifier = Modifier.size(keySize)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = "OK", tint = Color.White)
                    }
                }
            } else {
                Spacer(Modifier.size(keySize))
            }
            DigitKey('0', keySize, enabled) { onDigit('0') }
            Surface(onClick = onDelete, enabled = enabled, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(keySize)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Backspace,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(keySize * 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, keySize: Dp, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(keySize),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(
                "$digit",
                style = if (keySize < 60.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}
