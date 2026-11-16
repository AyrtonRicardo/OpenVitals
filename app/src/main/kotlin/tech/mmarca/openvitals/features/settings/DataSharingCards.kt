package tech.mmarca.openvitals.features.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.components.OpenVitalsTextButton
import tech.mmarca.openvitals.ui.components.OpenVitalsTonalButton
import tech.mmarca.openvitals.ui.theme.LayoutMetrics
import tech.mmarca.openvitals.ui.theme.Spacing

/** The master switch. Health Connect data also needs the background-read grant, offered under it. */
@Composable
internal fun DataSharingSwitchCard(
    enabled: Boolean,
    backgroundReadMissing: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onAllowBackgroundRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LayoutMetrics.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.data_sharing_switch_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.data_sharing_switch_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.padding(start = Spacing.md),
                )
            }
            if (enabled && backgroundReadMissing) {
                Text(
                    text = stringResource(R.string.data_sharing_background_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OpenVitalsTonalButton(onClick = onAllowBackgroundRead) {
                    Text(stringResource(R.string.data_sharing_background_action))
                }
            }
        }
    }
}

/** What each group holds, so the user knows what Android's dialog is asking about. */
@Composable
internal fun DataSharingGroupsCard(modifier: Modifier = Modifier) {
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LayoutMetrics.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            DataSharingGroup.entries.forEach { group ->
                Column {
                    Text(text = stringResource(group.titleRes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(group.summaryRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DataSharingNoAppsCard(modifier: Modifier = Modifier) {
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.data_sharing_apps_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(LayoutMetrics.cardPadding),
        )
    }
}

/** One app: what it may read now, when it last asked, and where to change that. */
@Composable
internal fun DataSharingAppCard(
    app: DataSharingAppUi,
    onManage: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LayoutMetrics.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(text = app.label, style = MaterialTheme.typography.titleSmall)
            // map is inline, so the composable call inside is allowed; joinToString's lambda is not.
            val allowed = app.allowedGroups.map { stringResource(it.titleRes) }.joinToString(separator = ", ")
            Text(
                text = if (allowed.isEmpty()) {
                    stringResource(R.string.data_sharing_app_none_allowed)
                } else {
                    stringResource(R.string.data_sharing_app_allowed, allowed)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.data_sharing_app_last_asked, app.lastAccessText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OpenVitalsTextButton(onClick = onManage) {
                    Text(stringResource(R.string.data_sharing_app_manage))
                }
                OpenVitalsTextButton(onClick = onForget) {
                    Text(stringResource(R.string.data_sharing_app_forget))
                }
            }
        }
    }
}

@get:StringRes
internal val DataSharingGroup.titleRes: Int
    get() = when (this) {
        DataSharingGroup.ACTIVITY -> R.string.data_sharing_group_activity_title
        DataSharingGroup.WORKOUTS -> R.string.data_sharing_group_workouts_title
        DataSharingGroup.ROUTES -> R.string.data_sharing_group_routes_title
        DataSharingGroup.SLEEP -> R.string.data_sharing_group_sleep_title
        DataSharingGroup.HEART -> R.string.data_sharing_group_heart_title
        DataSharingGroup.VITALS -> R.string.data_sharing_group_vitals_title
        DataSharingGroup.BODY -> R.string.data_sharing_group_body_title
        DataSharingGroup.NUTRITION -> R.string.data_sharing_group_nutrition_title
        DataSharingGroup.RECOVERY -> R.string.data_sharing_group_recovery_title
        DataSharingGroup.CYCLE -> R.string.data_sharing_group_cycle_title
        DataSharingGroup.SEXUAL_ACTIVITY -> R.string.data_sharing_group_sexual_activity_title
    }

@get:StringRes
internal val DataSharingGroup.summaryRes: Int
    get() = when (this) {
        DataSharingGroup.ACTIVITY -> R.string.data_sharing_group_activity_summary
        DataSharingGroup.WORKOUTS -> R.string.data_sharing_group_workouts_summary
        DataSharingGroup.ROUTES -> R.string.data_sharing_group_routes_summary
        DataSharingGroup.SLEEP -> R.string.data_sharing_group_sleep_summary
        DataSharingGroup.HEART -> R.string.data_sharing_group_heart_summary
        DataSharingGroup.VITALS -> R.string.data_sharing_group_vitals_summary
        DataSharingGroup.BODY -> R.string.data_sharing_group_body_summary
        DataSharingGroup.NUTRITION -> R.string.data_sharing_group_nutrition_summary
        DataSharingGroup.RECOVERY -> R.string.data_sharing_group_recovery_summary
        DataSharingGroup.CYCLE -> R.string.data_sharing_group_cycle_summary
        DataSharingGroup.SEXUAL_ACTIVITY -> R.string.data_sharing_group_sexual_activity_summary
    }
