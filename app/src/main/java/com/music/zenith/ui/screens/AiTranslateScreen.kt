package com.music.zenith.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.zenith.R
import com.music.zenith.data.lyrics.AiLyricsTranslator
import com.music.zenith.data.lyrics.AiProvider
import com.music.zenith.data.lyrics.AiProviderConfig
import com.music.zenith.data.settings.AppSettings
import com.music.zenith.ui.components.AiModelDialog
import com.music.zenith.ui.components.ChoiceAlert
import com.music.zenith.ui.components.IosSwitch
import com.music.zenith.ui.components.TextValueAlert
import com.music.zenith.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/**
 * The dialogs the AI translation page opens. Rendered by the activity, above
 * the tab bar and mini player, like [DiscordDialog].
 */
sealed interface AiTranslateDialog {
    data object Order : AiTranslateDialog
    data class Keys(val provider: AiProvider) : AiTranslateDialog
    data class Model(val provider: AiProvider) : AiTranslateDialog
}

private fun keyUrl(provider: AiProvider): String = when (provider) {
    AiProvider.GEMINI -> "aistudio.google.com/apikey"
    AiProvider.GROQ -> "console.groq.com/keys"
    AiProvider.OPENROUTER -> "openrouter.ai/keys"
}

/**
 * Lyrics translation through AI: the master switch, the order the providers
 * are tried in, and each provider's keys and model. Whatever is tried last is
 * the built-in translation, so a lyric always has a way to be translated.
 */
@Composable
fun AiTranslateScreen(
    onOpenDialog: (AiTranslateDialog) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val config by AppSettings.aiTranslate.collectAsStateWithLifecycle()
    val lastUsed by AiLyricsTranslator.lastUsed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<List<AiLyricsTranslator.ProviderTest>?>(null) }

    val ready = config.providers.any { it.usable }
    val builtIn = stringResource(R.string.ai_translate_builtin)
    val orderText = config.providers.joinToString(" → ") { it.provider.label } + " → " + builtIn
    val result = testResult
    val testSubtitle = when {
        testing -> stringResource(R.string.ai_translate_testing)
        result == null -> stringResource(R.string.ai_translate_test_subtitle)
        result.isEmpty() -> stringResource(R.string.ai_translate_test_none)
        else -> result.joinToString("\n") {
            "${if (it.ok) "✓" else "✗"} ${it.provider.label}: ${it.detail}"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.ai_translate))

        SettingsGroup(footer = stringResource(R.string.ai_translate_flow_footer)) {
            SettingsRow(
                icon = SfIcons.AutoAwesome,
                title = stringResource(R.string.ai_translate_use),
                subtitle = stringResource(
                    if (config.enabled && !ready) R.string.ai_translate_need_key
                    else R.string.ai_translate_use_subtitle,
                ),
                trailing = {
                    IosSwitch(
                        checked = config.enabled,
                        onCheckedChange = AppSettings::setAiTranslateEnabled,
                    )
                },
                onClick = { AppSettings.setAiTranslateEnabled(!config.enabled) },
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.Tune,
                title = stringResource(R.string.ai_translate_order),
                subtitle = orderText,
                enabled = config.enabled,
                onClick = { onOpenDialog(AiTranslateDialog.Order) },
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.CloudDone,
                title = stringResource(R.string.ai_translate_test),
                subtitle = testSubtitle,
                enabled = !testing,
                onClick = {
                    scope.launch {
                        testing = true
                        try {
                            testResult = AiLyricsTranslator.testAll(config)
                        } finally {
                            testing = false
                        }
                    }
                },
            )
            RowDivider()
            SettingsRow(
                icon = SfIcons.History,
                title = stringResource(R.string.ai_translate_last_used),
                subtitle = lastUsed.ifEmpty { stringResource(R.string.ai_translate_last_used_none) },
            )
        }

        config.providers.forEachIndexed { index, provider ->
            ProviderGroup(provider, index + 1, onOpenDialog)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProviderGroup(
    cfg: AiProviderConfig,
    priority: Int,
    onOpenDialog: (AiTranslateDialog) -> Unit,
) {
    val provider = cfg.provider
    val scope = rememberCoroutineScope()
    var testing by remember(provider) { mutableStateOf(false) }
    var results by remember(provider) { mutableStateOf<List<AiLyricsTranslator.KeyTest>?>(null) }
    val keyResults = results
    val testSubtitle = when {
        testing -> stringResource(R.string.ai_translate_testing)
        keyResults == null -> stringResource(R.string.ai_provider_test_subtitle)
        keyResults.isEmpty() -> stringResource(R.string.ai_provider_keys_none)
        else -> keyResults.joinToString("\n") {
            "${if (it.ok) "✓" else "✗"} #${it.keyNumber}: ${it.detail}"
        }
    }
    SettingsGroup(
        header = stringResource(R.string.ai_provider_header, provider.label, priority),
        footer = stringResource(R.string.ai_provider_get_key, keyUrl(provider)),
    ) {
        SettingsRow(
            icon = SfIcons.Cloud,
            title = stringResource(R.string.enabled),
            trailing = {
                IosSwitch(
                    checked = cfg.enabled,
                    onCheckedChange = { AppSettings.setAiProviderEnabled(provider, it) },
                )
            },
            onClick = { AppSettings.setAiProviderEnabled(provider, !cfg.enabled) },
        )
        RowDivider()
        SettingsRow(
            icon = SfIcons.Key,
            title = stringResource(R.string.ai_provider_keys),
            subtitle = if (cfg.keys.isEmpty()) {
                stringResource(R.string.ai_provider_keys_none)
            } else {
                stringResource(R.string.ai_provider_keys_count, cfg.keys.size)
            },
            onClick = { onOpenDialog(AiTranslateDialog.Keys(provider)) },
        )
        RowDivider()
        SettingsRow(
            icon = SfIcons.Tune,
            title = stringResource(R.string.ai_provider_model),
            subtitle = if (cfg.model.isBlank()) {
                stringResource(R.string.ai_provider_model_auto)
            } else {
                cfg.model
            },
            onClick = { onOpenDialog(AiTranslateDialog.Model(provider)) },
        )
        RowDivider()
        SettingsRow(
            icon = SfIcons.CloudDone,
            title = stringResource(R.string.ai_provider_test),
            subtitle = testSubtitle,
            enabled = !testing,
            onClick = {
                if (cfg.keys.isEmpty()) {
                    results = emptyList()
                } else {
                    scope.launch {
                        testing = true
                        try {
                            results = AiLyricsTranslator.testKeys(cfg)
                        } finally {
                            testing = false
                        }
                    }
                }
            },
        )
    }
}

private fun <T> permutations(items: List<T>): List<List<T>> =
    if (items.size <= 1) {
        listOf(items)
    } else {
        items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }
    }

@Composable
fun AiTranslateDialogHost(
    which: AiTranslateDialog,
    hazeState: HazeState,
    onDismiss: () -> Unit,
) {
    val config by AppSettings.aiTranslate.collectAsStateWithLifecycle()
    when (which) {
        AiTranslateDialog.Order -> {
            val options = remember { permutations(AiProvider.entries.toList()) }
            ChoiceAlert(
                hazeState = hazeState,
                title = stringResource(R.string.ai_translate_order),
                message = stringResource(R.string.ai_translate_order_message),
                options = options,
                selected = config.providers.map { it.provider },
                label = { order -> order.joinToString(" → ") { it.label } },
                onSelect = {
                    AppSettings.setAiProviderOrder(it)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        is AiTranslateDialog.Keys -> {
            val provider = which.provider
            val current = config.of(provider).keys
            var input by remember(provider) { mutableStateOf(current.joinToString(", ")) }
            TextValueAlert(
                hazeState = hazeState,
                title = stringResource(R.string.ai_provider_keys_title, provider.label),
                message = stringResource(R.string.ai_provider_keys_message),
                placeholder = "key1, key2",
                value = input,
                onValueChange = { input = it },
                onSave = {
                    AppSettings.setAiProviderKeys(provider, input)
                    onDismiss()
                },
                onDismiss = onDismiss,
                onRemove = if (current.isNotEmpty()) {
                    {
                        AppSettings.setAiProviderKeys(provider, "")
                        onDismiss()
                    }
                } else {
                    null
                },
            )
        }

        is AiTranslateDialog.Model -> AiModelDialog(
            hazeState = hazeState,
            provider = which.provider,
            onDismiss = onDismiss,
        )
    }
}