package com.anomalyco.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anomalyco.opencode.R
import com.anomalyco.opencode.domain.model.ModelInfo
import com.anomalyco.opencode.domain.model.ProviderConfig

/**
 * Bottom sheet listing every provider and its models with a live search box;
 * the active model carries a check. Selecting a model calls [onSelect], which
 * performs the `POST /config` switch and dismisses on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    isLoading: Boolean,
    switchingModelId: String?,
    onDismiss: () -> Unit,
    onSelect: (ModelInfo) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var query by remember { mutableStateOf("") }
    val visible = filterProviders(providers, query)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.model_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.model_picker_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.model_picker_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            if (isLoading && providers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                return@Column
            }
            if (visible.isEmpty()) {
                Text(
                    text = stringResource(
                        if (query.isNotBlank()) R.string.model_picker_no_match else R.string.model_picker_no_models,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
                visible.forEach { provider ->
                    item(key = "hdr-${provider.providerId}") {
                        Text(
                            text = provider.displayName +
                                if (provider.isConnected) "" else stringResource(R.string.model_picker_disconnected),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(provider.models, key = { it.qualifiedId }) { model ->
                        ModelRow(
                            model = model,
                            enabled = provider.isConnected,
                            isSwitching = switchingModelId == model.qualifiedId,
                            onClick = { onSelect(model) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pure catalog filter powering the sheet's search box. A provider-level match
 * keeps the whole provider; otherwise only the matching models are kept
 * (provider header stays as context). Case-insensitive on `displayName`,
 * `modelId`, `qualifiedId`, `providerId` and provider display name.
 */
internal fun filterProviders(
    providers: List<ProviderConfig>,
    query: String,
): List<ProviderConfig> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return providers
    return providers.mapNotNull { provider ->
        val providerMatches =
            provider.providerId.lowercase().contains(needle) ||
                provider.displayName.lowercase().contains(needle)
        if (providerMatches) {
            provider
        } else {
            val models = provider.models.filter { model ->
                model.displayName.lowercase().contains(needle) ||
                    model.modelId.lowercase().contains(needle) ||
                    model.qualifiedId.lowercase().contains(needle)
            }
            provider.takeIf { models.isNotEmpty() }?.copy(models = models)
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    enabled: Boolean,
    isSwitching: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (model.isCurrent) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(enabled = enabled && !isSwitching, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                append(model.modelId)
                model.contextLength?.let { append(" · ${it / 1000}k context") }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isSwitching) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (model.isCurrent) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.model_picker_current),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
