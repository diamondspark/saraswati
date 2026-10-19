/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the Family tab. Until Phase 4 brings Parivaar Mix,
// family playlists and Sangat, it is the doorway to the family hub: "Manage
// family" on the owner's phone, "Join the family" on one that is not paired
// yet, and a quiet "part of the family" note once it is. Later phases extend
// this screen rather than adding a parallel one.
package com.metrolist.music.saraswati.family

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.IsFamilyOwnerKey
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.rememberPreference

@Composable
fun FamilyScreen(
    navController: NavController,
    viewModel: FamilyViewModel = hiltViewModel(),
) {
    val (isOwner) = rememberPreference(IsFamilyOwnerKey, defaultValue = false)
    val isMember by viewModel.isMember.collectAsStateWithLifecycle()
    val hasPendingClaim by viewModel.hasPendingClaim.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshMembership() }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        when {
            isOwner ->
                Material3SettingsGroup(
                    items =
                        listOf(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.group_outlined),
                                title = { Text(stringResource(R.string.family_manage_title)) },
                                description = { Text(stringResource(R.string.family_manage_desc)) },
                                trailingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.navigate_next),
                                        contentDescription = null,
                                    )
                                },
                                onClick = { navController.navigate(FamilyRoutes.MANAGE) },
                            ),
                        ),
                )

            isMember ->
                Material3SettingsGroup(
                    items =
                        listOf(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.group_filled),
                                title = { Text(stringResource(R.string.family_paired_title)) },
                                description = { Text(stringResource(R.string.family_paired_desc)) },
                            ),
                        ),
                )

            else -> {
                Material3SettingsGroup(
                    items =
                        listOf(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.group_add),
                                title = { Text(stringResource(R.string.family_join_title)) },
                                description = { Text(stringResource(R.string.family_join_desc)) },
                                trailingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.navigate_next),
                                        contentDescription = null,
                                    )
                                },
                                onClick = { navController.navigate(FamilyRoutes.JOIN) },
                            ),
                        ),
                )
                // SARASWATI: fix wave A (M2) — a phone that has asked to join and waits for approval is
                // not the owner's, so the way to paste an admin key is not offered on it.
                if (!hasPendingClaim) {
                    TextButton(onClick = { navController.navigate(FamilyRoutes.MANAGE) }) {
                        Text(
                            text = stringResource(R.string.family_owner_setup_link),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
