package com.music.zenith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.zenith.R
import com.music.zenith.data.lyrics.AiLyricsTranslator
import com.music.zenith.data.lyrics.AiProvider
import com.music.zenith.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

private val MODEL_LIST_MAX_HEIGHT = 340.dp

private sealed interface ModelsState {
    data object Loading : ModelsState
    data class Failed(val reason: String) : ModelsState
    data class Ready(val models: List<String>) : ModelsState
}

/**
 * Picks the model a provider translates with. The list is not typed in or
 * guessed: it is asked of the provider with the user's own API key, so every
 * row is a model that key can really use. The first row is "automatic", which
 * leaves the choice to the app each time.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AiModelDialog(
    hazeState: HazeState,
    provider: AiProvider,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config by AppSettings.aiTranslate.collectAsStateWithLifecycle()
    val selected = config.of(provider).model.trim()
    val shape = RoundedCornerShape(ALERT_CORNER)
    val motion = rememberMacOSPopupState(onClosed = onDismiss)
    var query by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<ModelsState>(ModelsState.Loading) }
    val noKey = stringResource(R.string.ai_provider_keys_none)

    // The first key that answers supplies the list; a key that fails is skipped.
    LaunchedEffect(provider, attempt) {
        state = ModelsState.Loading
        val keys = AppSettings.aiTranslate.value.of(provider).keys
        if (keys.isEmpty()) {
            state = ModelsState.Failed(noKey)
            return@LaunchedEffect
        }
        var failure = ""
        for (key in keys) {
            val list = AiLyricsTranslator.listModels(provider, key)
            val error = list.error
            if (error == null) {
                state = ModelsState.Ready(list.models)
                return@LaunchedEffect
            }
            failure = error
        }
        state = ModelsState.Failed(failure)
    }

    val shown = remember(state, query) {
        val ready = (state as? ModelsState.Ready)?.models.orEmpty()
        val needle = query.trim()
        if (needle.isEmpty()) ready else ready.filter { it.contains(needle, ignoreCase = true) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .macOSPopupScrim(motion, SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = motion::requestClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .macOSPopupMotion(motion)
                .clip(shape)
                .alertSurface(hazeState, frosted = true, shape = shape)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .macOSPopupContent(motion),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.ai_provider_model_title, provider.label),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.ai_provider_model_message),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }

            AlertRule()
            ModelSearchField(query = query, onQueryChange = { query = it })
            AlertRule()

            LazyColumn(modifier = Modifier.heightIn(max = MODEL_LIST_MAX_HEIGHT)) {
                when (val current = state) {
                    ModelsState.Loading -> item {
                        ModelStatusText(stringResource(R.string.ai_model_loading))
                    }

                    is ModelsState.Failed -> {
                        item { ModelStatusText(current.reason) }
                        item {
                            AlertRule()
                            ModelChoiceRow(
                                label = stringResource(R.string.ai_model_retry),
                                detail = null,
                                selected = false,
                                onClick = { attempt++ },
                            )
                        }
                    }

                    is ModelsState.Ready -> {
                        if (query.isBlank()) {
                            item {
                                ModelChoiceRow(
                                    label = stringResource(R.string.ai_provider_model_auto),
                                    detail = stringResource(R.string.ai_provider_model_auto_detail),
                                    selected = selected.isEmpty(),
                                    onClick = {
                                        AppSettings.setAiProviderModel(provider, "")
                                        onDismiss()
                                    },
                                )
                            }
                        }
                        items(shown, key = { it }) { id ->
                            AlertRule()
                            ModelChoiceRow(
                                label = id,
                                detail = null,
                                selected = selected == id,
                                onClick = {
                                    AppSettings.setAiProviderModel(provider, id)
                                    onDismiss()
                                },
                            )
                        }
                        if (shown.isEmpty()) {
                            item { ModelStatusText(stringResource(R.string.ai_model_none)) }
                        }
                    }
                }
            }

            AlertRule()
            AlertAction(
                label = stringResource(R.string.done),
                emphasised = true,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun ModelStatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
    )
}

@Composable
private fun ModelSearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.ai_model_search),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModelChoiceRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}