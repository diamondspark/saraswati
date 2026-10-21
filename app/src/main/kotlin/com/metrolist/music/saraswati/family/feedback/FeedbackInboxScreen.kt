/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feedback — Manage family, "Feedback inbox": what the family sent, newest first, with a status the owner
// can change. A report's text, name and phone info are untrusted: every one is shown as a plain Text, never parsed
// as markdown, HTML or links. The one tappable link is the GitHub issue, and only a github.com address is opened.
package com.metrolist.music.saraswati.family.feedback

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain

private const val COLLAPSED_LINES = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackInboxScreen(
    navController: NavController,
    viewModel: FeedbackInboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.feedback_inbox_title)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = stringResource(R.string.back_button_desc),
                    )
                }
            },
        )

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InboxFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(stringResource(filter.labelRes())) },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val uriHandler = LocalUriHandler.current
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { failure ->
                    item(key = "error") { Notice(stringResource(failure.messageRes()), isError = true) }
                }
                state.changeError?.let { failure ->
                    item(key = "change-error") {
                        Notice(
                            stringResource(R.string.feedback_inbox_change_failed, stringResource(failure.messageRes())),
                            isError = true,
                            onDismiss = viewModel::dismissChangeError,
                        )
                    }
                }
                if (state.items.isEmpty() && state.loaded && state.error == null) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.feedback_inbox_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    InboxItem(
                        item = item,
                        expanded = item.id in state.expanded,
                        changing = item.id in state.pendingChanges,
                        onToggle = { viewModel.toggleExpanded(item.id) },
                        onSetStatus = { viewModel.setStatus(item.id, it) },
                        onOpenIssue = { url ->
                            // The check is repeated here so no caller can reach the handler with an unchecked address.
                            safeIssueUrl(url)?.let { safe -> runCatching { uriHandler.openUri(safe) } }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(
    text: String,
    isError: Boolean,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (onDismiss != null) {
                M3IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.close))
                }
            }
        }
    }
}

@Composable
private fun InboxItem(
    item: FeedbackItem,
    expanded: Boolean,
    changing: Boolean,
    onToggle: () -> Unit,
    onSetStatus: (FeedbackStatus) -> Unit,
    onOpenIssue: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val issueUrl = safeIssueUrl(item.issueUrl)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (item.kind == FeedbackKind.Bug) R.string.feedback_kind_bug else R.string.feedback_kind_idea),
                    style = MaterialTheme.typography.titleSmall,
                )
                StatusChip(item.status)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    M3IconButton(onClick = { menuOpen = true }, enabled = !changing) {
                        Icon(painterResource(R.drawable.more_vert), contentDescription = stringResource(R.string.feedback_inbox_item_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MENU_STATUSES.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(stringResource(status.markRes())) },
                                enabled = status != item.status,
                                onClick = {
                                    menuOpen = false
                                    onSetStatus(status)
                                },
                            )
                        }
                    }
                }
            }

            // Plain Text on purpose: the report is untrusted, so nothing in it is rendered, linked or parsed.
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp).clickable(onClickLabel = stringResource(if (expanded) R.string.feedback_inbox_collapse else R.string.feedback_inbox_expand), onClick = onToggle),
            )

            Text(
                text = metaLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )

            if (issueUrl != null) {
                TextButton(onClick = { onOpenIssue(issueUrl) }) {
                    Text(stringResource(R.string.feedback_inbox_open_issue))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: FeedbackStatus) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            stringResource(status.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun metaLine(item: FeedbackItem): String {
    val whenText =
        DateUtils
            .getRelativeTimeSpanString(item.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            .toString()
    val info = item.diagnostics
    return if (info == null) {
        stringResource(R.string.feedback_inbox_meta, item.nickname, whenText)
    } else {
        stringResource(R.string.feedback_inbox_meta_with_info, item.nickname, whenText, info.appVersion, info.device)
    }
}

private val MENU_STATUSES = listOf(FeedbackStatus.Seen, FeedbackStatus.Planned, FeedbackStatus.Done, FeedbackStatus.WontFix)

private fun InboxFilter.labelRes(): Int =
    when (this) {
        InboxFilter.Open -> R.string.feedback_inbox_filter_open
        InboxFilter.Done -> R.string.feedback_inbox_filter_done
        InboxFilter.All -> R.string.feedback_inbox_filter_all
    }

private fun FeedbackStatus.labelRes(): Int =
    when (this) {
        FeedbackStatus.New -> R.string.feedback_inbox_status_new
        FeedbackStatus.Seen -> R.string.feedback_inbox_status_seen
        FeedbackStatus.Planned -> R.string.feedback_inbox_status_planned
        FeedbackStatus.Done -> R.string.feedback_inbox_status_done
        FeedbackStatus.WontFix -> R.string.feedback_inbox_status_wontfix
    }

private fun FeedbackStatus.markRes(): Int =
    when (this) {
        FeedbackStatus.Seen -> R.string.feedback_inbox_mark_seen
        FeedbackStatus.Planned -> R.string.feedback_inbox_mark_planned
        FeedbackStatus.Done -> R.string.feedback_inbox_mark_done
        FeedbackStatus.WontFix -> R.string.feedback_inbox_mark_wontfix
        FeedbackStatus.New -> R.string.feedback_inbox_status_new
    }

private fun InboxFailure.messageRes(): Int =
    when (this) {
        InboxFailure.NoKey -> R.string.feedback_inbox_error_no_key
        InboxFailure.KeyRejected -> R.string.feedback_inbox_error_key_rejected
        InboxFailure.Offline -> R.string.feedback_inbox_error_offline
        InboxFailure.RateLimited -> R.string.feedback_inbox_error_rate_limited
        InboxFailure.NotAvailable -> R.string.feedback_inbox_error_not_available
        InboxFailure.ItemGone -> R.string.feedback_inbox_error_item_gone
        InboxFailure.Other -> R.string.feedback_inbox_error_other
    }
