/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the Family tab. Until Phase 4 brings Parivaar Mix,
// family playlists and Sangat, it is the doorway to the family hub: "Manage
// family" on the owner's phone, "Join the family" on one that is not paired
// yet, and a quiet "part of the family" note once it is. A phone that has asked
// to join and waits for the owner shows "Waiting for approval" with its code and
// an Open Join button instead. Later phases extend this screen rather than
// adding a parallel one.
package com.metrolist.music.saraswati.family

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.IsFamilyOwnerKey
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixCard
import com.metrolist.music.saraswati.family.parivaar.ParivaarViewModel
import com.metrolist.music.saraswati.family.playlists.FamilyPlaylistsSection
import com.metrolist.music.saraswati.family.playlists.FamilyPlaylistsViewModel
import com.metrolist.music.utils.rememberPreference

@Composable
fun FamilyScreen(
    navController: NavController,
    viewModel: FamilyViewModel = hiltViewModel(),
) {
    val (isOwner) = rememberPreference(IsFamilyOwnerKey, defaultValue = false)
    val isMember by viewModel.isMember.collectAsStateWithLifecycle()
    val hasPendingClaim by viewModel.hasPendingClaim.collectAsStateWithLifecycle()
    val claimCode by viewModel.pendingClaimCode.collectAsStateWithLifecycle()

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
                val code = claimCode
                if (code != null) {
                    // SARASWATI: fix wave B1 — a claim is stored and the owner has not approved it: say so, with the code
                    // to read out, instead of offering to start a join that is already under way.
                    Material3SettingsGroup(
                        items =
                            listOf(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.group_add),
                                    title = { Text(stringResource(R.string.family_waiting_title, code)) },
                                    description = { Text(stringResource(R.string.family_waiting_desc)) },
                                ),
                            ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { navController.navigate(FamilyRoutes.JOIN) }) {
                        Text(stringResource(R.string.family_waiting_open_join))
                    }
                } else {
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
                }
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

        // SARASWATI: Phase 4 Task 20 — the shared sections need this phone's session, so only the owner's and a
        // paired phone see them.
        if (isOwner || isMember) {
            FamilySharedSections(navController)
        }
    }
}

@Composable
private fun FamilySharedSections(navController: NavController) {
    val parivaarViewModel: ParivaarViewModel = hiltViewModel()
    val playlistsViewModel: FamilyPlaylistsViewModel = hiltViewModel()
    val playerConnection = LocalPlayerConnection.current
    val parivaarState by parivaarViewModel.state.collectAsStateWithLifecycle()
    val tasteVisible by parivaarViewModel.tasteVisible.collectAsStateWithLifecycle()

    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.parivaar_mix_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    ParivaarMixCard(
        state = parivaarState,
        onPlay = { parivaarViewModel.play { playerConnection?.playQueue(it) } },
    )
    if (tasteVisible) {
        Text(
            stringResource(R.string.parivaar_mix_footnote) + " " + stringResource(R.string.parivaar_mix_change_taste),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).clickable { navController.navigate(FamilyRoutes.TASTE) },
        )
    }
    Spacer(Modifier.height(14.dp))
    FamilyPlaylistsSection(navController, playlistsViewModel)
    Spacer(Modifier.height(24.dp))
}
