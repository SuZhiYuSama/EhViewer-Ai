package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.zhanghai.compose.preference.DropdownListPreference

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.AiSettingsScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_ai)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            var baseUrl by rememberSaveable { mutableStateOf(Settings.aiBaseUrl.value.orEmpty()) }
            var apiKey by rememberSaveable { mutableStateOf(Settings.aiApiKey.value.orEmpty()) }
            var defaultModel by rememberSaveable { mutableStateOf(Settings.aiDefaultModel.value.orEmpty()) }

            AiTextField(
                label = stringResource(id = R.string.settings_ai_base_url),
                placeholder = stringResource(id = R.string.settings_ai_base_url_placeholder),
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    Settings.aiBaseUrl.value = it.ifBlank { null }
                },
                // 修正参数名
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )

            Spacer(modifier = Modifier.height(12.dp))

            AiTextField(
                label = stringResource(id = R.string.settings_ai_api_key),
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    Settings.aiApiKey.value = it.ifBlank { null }
                },
                visualTransformation = PasswordVisualTransformation(),
                // 修正参数名
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )

            Spacer(modifier = Modifier.height(12.dp))

            val formatEntries = stringArrayResource(id = com.hippo.ehviewer.R.array.ai_api_format_entries)
            val formatValues = stringArrayResource(id = com.hippo.ehviewer.R.array.ai_api_format_entry_values)
            val formatMap = remember { formatValues.zip(formatEntries).toMap() }
            val formatState = Settings.aiApiFormat.asMutableState()

            // 【修改】将标题直接硬编码为 "API 格式"
            DropdownListPreference(
                state = formatState,
                items = formatMap,
                title = { Text("API 格式") },
                summary = { Text(formatMap[formatState.value].orEmpty()) },
            )

            Spacer(modifier = Modifier.height(12.dp))

            AiTextField(
                label = stringResource(id = R.string.settings_ai_default_model),
                placeholder = stringResource(id = R.string.settings_ai_default_model_placeholder),
                value = defaultModel,
                onValueChange = {
                    defaultModel = it
                    Settings.aiDefaultModel.value = it.ifBlank { null }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AiTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        placeholder = placeholder?.let { { Text(text = it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = true,
    )
}