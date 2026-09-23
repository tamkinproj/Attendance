package com.muslimedu.attendance.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The reference mockup's cards/buttons/fields are all heavily rounded
 * (roughly 16-28dp corners), not Material 3's tighter defaults - applied
 * globally here so every `Card`/`Button`/`OutlinedTextField` picks it up
 * automatically without each screen hardcoding its own `RoundedCornerShape`.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
