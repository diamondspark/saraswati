/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — pieces the Family, Manage Family and Join screens share.
package com.metrolist.music.saraswati.family

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

object FamilyRoutes {
    const val MANAGE = "family/manage"
    const val JOIN = "family/join"
}

@StringRes
fun FamilyError.messageRes(): Int =
    when (this) {
        FamilyError.NO_ADMIN_KEY -> R.string.family_error_no_admin_key
        FamilyError.INVALID_INPUT -> R.string.family_error_invalid_input
        FamilyError.UNAUTHORIZED -> R.string.family_error_unauthorized
        FamilyError.NOT_FOUND -> R.string.family_error_not_found
        FamilyError.GONE -> R.string.family_error_gone
        FamilyError.EXPIRED -> R.string.family_error_expired
        FamilyError.RATE_LIMITED -> R.string.family_error_rate_limited
        FamilyError.OFFLINE -> R.string.family_error_offline
        FamilyError.NOT_CONFIGURED -> R.string.family_error_not_configured
        FamilyError.SERVER -> R.string.family_error_server
        FamilyError.STATION_LIMIT -> R.string.family_error_station_limit
        FamilyError.UNKNOWN -> R.string.family_error_unknown
    }

/** A failure the person should see, announced to screen readers when it appears. */
@Composable
fun FamilyErrorBanner(
    error: FamilyError,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
