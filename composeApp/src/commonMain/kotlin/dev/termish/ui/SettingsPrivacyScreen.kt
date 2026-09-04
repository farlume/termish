package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import dev.termish.ui.theme.Spacing
import dev.termish.util.monospaceFontFamily

/** 可离线查看的应用隐私政策；在线版本用于 Google Play 商店链接。 */
@Composable
fun SettingsPrivacyScreen(onBack: () -> Unit) {
    val s = LocalAppStrings.current
    val p = s.permissions
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                p.privacyTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        ) {
            Text(
                p.privacySummary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.Xl))
            PrivacySection(p.privacyLocalTitle, p.privacyLocalBody)
            PrivacySection(p.privacyConnectionsTitle, p.privacyConnectionsBody)
            PrivacySection(p.privacyVoiceTitle, p.privacyVoiceBody)
            PrivacySection(p.privacyAgentsTitle, p.privacyAgentsBody)
            PrivacySection(p.privacyDiagnosticsTitle, p.privacyDiagnosticsBody)
            PrivacySection(p.privacyControlTitle, p.privacyControlBody)
            PrivacySection(p.privacyDeletionTitle, p.privacyDeletionBody)
            Text(
                p.privacyUpdated,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) {
                Text(p.privacyOpenWeb)
            }
            Spacer(Modifier.height(Spacing.Xl))
        }
    }
}

@Composable
private fun PrivacySection(
    title: String,
    body: String,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(Spacing.Sm))
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.Xl))
}
