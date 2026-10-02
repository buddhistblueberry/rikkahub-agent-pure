package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentDefinitionRepository
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * P2-06b — the expert library.
 *
 * #36 shipped this screen over the DataStore-backed `Settings.subAgents` list; P2-06b retires that
 * list and backs the same screen with [AgentDefinitionRepository] (its own `agent_definitions.db`).
 * The UI is otherwise deliberately unchanged: this is the "mechanical" half of the source-of-truth
 * switch, so the expert-editing *surface* (tool face, MCP servers, skills, the D9 namespace picker)
 * stays a P2-06c concern while list / add / edit / delete keep working today.
 *
 * Name resolution for `subagent_dispatch` is case-insensitive, so a second expert whose name only
 * differs by case would make dispatch ambiguous. That is rejected here (as before) rather than being
 * allowed to reach the resolver at dispatch time.
 */
@Composable
fun SettingSubAgentsPage(
    vm: SettingVM = koinViewModel(),
    agentDefinitionRepository: AgentDefinitionRepository = koinInject(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val definitions by agentDefinitionRepository.definitions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val editState = useEditState<AgentDefinition> { edited ->
        // A create and an update differ only in whether the row already exists. `update` refuses to
        // insert (a stale id must not resurrect a deleted expert), so the branch is explicit.
        scope.launch {
            if (definitions.any { it.id == edited.id }) {
                agentDefinitionRepository.update(edited)
            } else {
                agentDefinitionRepository.create(
                    name = edited.name,
                    description = edited.description,
                    systemPrompt = edited.systemPrompt,
                    modelId = edited.modelId,
                    enabled = edited.enabled,
                    localTools = edited.localTools,
                    disabledLocalTools = edited.disabledLocalTools,
                    mcpServers = edited.mcpServers,
                    skills = edited.skills,
                    slug = edited.slug,
                    tokenBudget = edited.tokenBudget,
                    id = edited.id,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_sub_agents_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .floatingToolbarVerticalNestedScroll(
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onCollapse = { expanded = false },
                    ),
                contentPadding = innerPadding + PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (definitions.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxHeight(0.8f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                } else {
                    items(definitions, key = { it.id }) { definition ->
                        AgentDefinitionCard(
                            definition = definition,
                            onEdit = { editState.open(definition) },
                            onDelete = { scope.launch { agentDefinitionRepository.delete(definition.id) } },
                        )
                    }
                }
            }

            HorizontalFloatingToolbar(
                expanded = expanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
            ) {
                Button(onClick = {
                    editState.open(AgentDefinition(id = Uuid.random().toString(), name = ""))
                }) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HugeIcons.Add01, null)
                        AnimatedVisibility(expanded) {
                            Row {
                                Text(stringResource(R.string.setting_sub_agents_page_add))
                            }
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            AgentDefinitionEditSheet(
                definition = state,
                providers = settings.providers,
                existingDefinitions = definitions,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it },
            )
        }
    }
}

@Composable
private fun AgentDefinitionCard(
    definition: AgentDefinition,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.setting_sub_agents_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = definition.name.ifEmpty { stringResource(R.string.setting_sub_agents_page_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (definition.description.isNotEmpty()) {
                        Text(
                            text = definition.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!definition.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.setting_sub_agents_page_disabled))
                        }
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Tools, stringResource(R.string.setting_sub_agents_page_edit))
                }
            }
        }
    }
}

@Composable
private fun AgentDefinitionEditSheet(
    definition: AgentDefinition,
    providers: List<ProviderSetting>,
    existingDefinitions: List<AgentDefinition>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (AgentDefinition) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    // Resolution matches experts by name case-insensitively (AgentDefinitionResolver); saving a
    // second expert with a name that only differs by case would make dispatch ambiguous, so reject
    // it here rather than letting the ambiguity reach the resolver at dispatch time.
    val nameDuplicate = existingDefinitions.any {
        it.id != definition.id && it.name.isNotBlank() &&
            it.name.equals(definition.name, ignoreCase = true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_sub_agents_page_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = definition.name,
                    onValueChange = { onEdit(definition.copy(name = it)) },
                    label = { Text(stringResource(R.string.setting_sub_agents_page_name)) },
                    singleLine = true,
                    isError = nameDuplicate,
                    supportingText = if (nameDuplicate) {
                        { Text(stringResource(R.string.setting_sub_agents_page_name_duplicate)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = definition.description,
                    onValueChange = { onEdit(definition.copy(description = it)) },
                    label = { Text(stringResource(R.string.setting_sub_agents_page_description)) },
                    supportingText = { Text(stringResource(R.string.setting_sub_agents_page_description_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                FormItem(
                    label = { Text(stringResource(R.string.setting_sub_agents_page_enabled)) },
                    tail = {
                        Switch(
                            checked = definition.enabled,
                            onCheckedChange = { onEdit(definition.copy(enabled = it)) },
                        )
                    },
                )

                FormItem(
                    label = { Text(stringResource(R.string.setting_sub_agents_page_model)) },
                    description = { Text(stringResource(R.string.setting_sub_agents_page_model_desc)) },
                    content = {
                        ModelSelector(
                            // The store keeps the model id as its canonical Uuid string; the picker
                            // speaks Uuid. A value that no longer parses (hand-edited data) reads as
                            // "no model chosen" here rather than crashing the sheet.
                            modelId = definition.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                            providers = providers,
                            type = ModelType.CHAT,
                            allowClear = true,
                            onSelect = { model ->
                                // ModelSelector's clear button calls onSelect(Model()), whose
                                // default modelId is "" - no real model ever has a blank
                                // provider model id, so that's the clear signal.
                                onEdit(
                                    definition.copy(
                                        modelId = model.id
                                            .takeIf { model.modelId.isNotBlank() }
                                            ?.toString(),
                                    ),
                                )
                            },
                        )
                    },
                )

                OutlinedTextField(
                    value = definition.systemPrompt,
                    onValueChange = { onEdit(definition.copy(systemPrompt = it)) },
                    label = { Text(stringResource(R.string.setting_sub_agents_page_system_prompt)) },
                    supportingText = { Text(stringResource(R.string.setting_sub_agents_page_system_prompt_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    minLines = 4,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.setting_sub_agents_page_cancel))
                }
                TextButton(
                    onClick = onConfirm,
                    enabled = definition.name.isNotBlank() && !nameDuplicate,
                ) {
                    Text(stringResource(R.string.setting_sub_agents_page_confirm))
                }
            }
        }
    }
}
