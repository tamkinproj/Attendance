package com.muslimedu.attendance.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muslimedu.attendance.ui.components.BrandBackdrop
import com.muslimedu.attendance.ui.components.BrandLogo
import com.muslimedu.attendance.ui.theme.BrandTeal

/** Shown only while the stored session is being restored - usually a blink, since a cached admin opens instantly. */
@Composable
fun SplashScreen() {
    BrandBackdrop {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandLogo(size = 112.dp)
            Text(
                text = "Gate Attendance",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 24.dp),
            )
            CircularProgressIndicator(
                color = BrandTeal,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(top = 32.dp).size(24.dp),
            )
        }
    }
}
