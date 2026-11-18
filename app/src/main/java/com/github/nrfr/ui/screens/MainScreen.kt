package com.github.nrfr.ui.screens

import android.widget.Toast
import android.os.Build
import android.telephony.AccessNetworkConstants
import android.telephony.ims.RegistrationManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.github.nrfr.model.OperatorNumeric
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.nrfr.R
import com.github.nrfr.data.CountryPresets
import com.github.nrfr.data.PresetCarriers
import com.github.nrfr.manager.CarrierConfigManager
import com.github.nrfr.model.SimCardInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private data class ApplySettings(
    val sim: SimCardInfo,
    val countryCode: String,
    val carrierName: String?,
    val numeric: String?,
) {
    val hasDisplaySettings: Boolean get() = countryCode.isNotEmpty() || !carrierName.isNullOrEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onShowAbout: () -> Unit) {
    val context = LocalContext.current
    var editor by remember { mutableStateOf(SimEditorState()) }
    var isSimCardMenuExpanded by remember { mutableStateOf(false) }
    var isCountryCodeMenuExpanded by remember(editor.subId) { mutableStateOf(false) }
    var isCarrierMenuExpanded by remember(editor.subId) { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var simCards by remember { mutableStateOf<List<SimCardInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isApplying by remember { mutableStateOf(false) }
    var pendingImsConfirmation by remember { mutableStateOf<ApplySettings?>(null) }
    var simError by remember { mutableStateOf<String?>(null) }
    val selectedSimCard = simCards.find { it.subId == editor.subId }
    val canEdit = selectedSimCard != null && !isLoading && !isApplying
    val scope = rememberCoroutineScope()

    fun submit(settings: ApplySettings) {
        val (sim, countryCode, carrierName, numeric) = settings
        editor = editor.copy(operationMessage = null, changeNumeric = numeric != null)
        isApplying = true
        scope.launch {
            try {
                val result = CarrierConfigManager.setCarrierConfig(context, sim.subId, countryCode, carrierName, numeric)
                val verified = Build.VERSION.SDK_INT >= 29
                val message = buildString {
                    append("SIM ${sim.slot}：${if (verified) "以下讀取值已回讀確認" else "以下設定已提交"}。")
                    if (countryCode.isNotEmpty()) append("\nSIM 國家碼：${countryCode.lowercase(Locale.ROOT)}")
                    carrierName?.let { append("\n電信業者名稱：$it") }
                    numeric?.let {
                        append("\nSIM 電信業者代碼：$it")
                        append("\n請核對 IMS 通話、APN 與行動數據。若 IMS 失去註冊，請還原原代碼。")
                    }
                    append(if (result.persistent) "\n設定已儲存。" else "\n重新啟動、更換 SIM 或系統重載後可能需要重新套用。")
                }
                editor = editor.copy(operationMessage = message)
                Toast.makeText(context, "SIM ${sim.slot}：${if (verified) "回讀已確認" else "設定已提交"}", Toast.LENGTH_LONG).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                editor = editor.copy(operationMessage = "SIM ${sim.slot} 套用失敗：${error.message}")
                Toast.makeText(context, editor.operationMessage, Toast.LENGTH_LONG).show()
            } finally {
                isApplying = false
                refreshTrigger += 1
            }
        }
    }

    LaunchedEffect(context, refreshTrigger) {
        isLoading = true
        simError = null
        try {
            simCards = CarrierConfigManager.getSimCards(context)
            editor = editor.selectSim(editor.subId.takeIf { id -> simCards.any { it.subId == id } })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            simCards = emptyList()
            editor = editor.selectSim(null)
            simError = error.message ?: "讀取 SIM 卡失敗"
        } finally {
            isLoading = false
        }
    }

    pendingImsConfirmation?.let { settings ->
        val originalNumericActive = settings.sim.simOperator.isNotEmpty() &&
            settings.sim.simOperator == settings.sim.subscriptionOperator
        val keepOriginal = originalNumericActive && settings.hasDisplaySettings
        AlertDialog(
            onDismissRequest = { pendingImsConfirmation = null },
            title = { Text("SIM ${settings.sim.slot} 的 IMS 通話") },
            text = {
                Text(buildString {
                    append("三星會用 SIM 代碼建立 IMS 註冊。修改代碼可能讓 VoLTE 和 Wi-Fi 通話失去註冊。")
                    if (!originalNumericActive) append("\n\n若要恢復原代碼，請返回並選擇「還原設定」。")
                })
            },
            confirmButton = {
                if (keepOriginal) {
                    TextButton(onClick = {
                        pendingImsConfirmation = null
                        submit(settings.copy(numeric = null))
                    }) { Text("僅套用國家碼與名稱") }
                } else {
                    TextButton(onClick = { pendingImsConfirmation = null }) { Text("返回") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImsConfirmation = null
                    submit(settings)
                }) { Text("繼續測試代碼") }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            modifier = Modifier.size(48.dp),
                            contentDescription = "應用程式圖示",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Nrfr")
                    }
                },
                actions = {
                    IconButton(onClick = onShowAbout) {
                        Icon(Icons.Default.Info, contentDescription = "關於")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoading || isApplying) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            simError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refreshTrigger += 1 }, enabled = !isLoading && !isApplying) {
                    Text("重新讀取 SIM 卡")
                }
            }
            // SIM卡選擇
            SimCardSelector(
                simCards = simCards,
                selectedSimCard = selectedSimCard,
                isExpanded = isSimCardMenuExpanded,
                onExpandedChange = { isSimCardMenuExpanded = it },
                enabled = !isLoading && !isApplying,
                onSimCardSelected = { editor = editor.selectSim(it.subId) }
            )

            // 顯示目前選中的 SIM 卡的設定資訊
            selectedSimCard?.let { simCard ->
                CurrentConfigCard(simCard = simCard, canRefresh = canEdit, onRefresh = { refreshTrigger += 1 })
            }

            editor.operationMessage?.let { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("上次操作結果", style = MaterialTheme.typography.titleMedium)
                        Text(message)
                    }
                }
            }
            Text(
                selectedSimCard?.let { "待套用設定（SIM ${it.slot}）" } ?: "請先選擇 SIM 卡",
                style = MaterialTheme.typography.titleMedium,
            )

            // 國家碼選擇
            CountryCodeSelector(
                selectedCountryCode = editor.selectedCountryCode,
                isCustomCountryCode = editor.isCustomCountryCode,
                customCountryCode = editor.customCountryCode,
                isExpanded = isCountryCodeMenuExpanded,
                onExpandedChange = { isCountryCodeMenuExpanded = it },
                onCountryCodeSelected = { code ->
                    editor = editor.copy(selectedCountryCode = code, isCustomCountryCode = false)
                },
                enabled = canEdit,
                onCustomSelected = {
                    editor = editor.copy(isCustomCountryCode = true, selectedCountryCode = editor.customCountryCode)
                }
            )

            // 自訂國家碼輸入框
            if (editor.isCustomCountryCode) {
                CustomCountryCodeInput(
                    value = editor.customCountryCode,
                    enabled = canEdit,
                    onValueChange = {
                        if (it.length <= 2 && it.all { char -> char in 'A'..'Z' || char in 'a'..'z' }) {
                            editor = editor.copy(customCountryCode = it.uppercase(Locale.ROOT),
                                selectedCountryCode = it.uppercase(Locale.ROOT))
                        }
                    }
                )
            }

            // 電信業者選擇
            CarrierSelector(
                selectedCarrier = editor.selectedCarrier,
                isExpanded = isCarrierMenuExpanded,
                onExpandedChange = { isCarrierMenuExpanded = it },
                enabled = canEdit,
                onCarrierSelected = { carrier ->
                    editor = editor.copy(selectedCarrier = carrier, customCarrierName = carrier.displayName,
                        operatorNumeric = carrier.operatorNumeric)
                    if (carrier.region.isNotEmpty()) {
                        editor = editor.copy(selectedCountryCode = carrier.region, isCustomCountryCode = false)
                    }
                }
            )

            // 自訂電信業者名稱輸入框
            if (editor.selectedCarrier?.name == "自訂") {
                CustomCarrierNameInput(
                    value = editor.customCarrierName,
                    enabled = canEdit,
                    onValueChange = { editor = editor.copy(customCarrierName = it) }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = editor.changeNumeric, onCheckedChange = { editor = editor.copy(changeNumeric = it) },
                    enabled = canEdit && Build.VERSION.SDK_INT >= 29)
                Text("修改 SIM 電信業者代碼")
            }
            if (Build.VERSION.SDK_INT < 29) Text("電信業者代碼覆寫需要 Android 10 以上。")
            if (editor.changeNumeric) {
                val valid = OperatorNumeric.isValid(editor.operatorNumeric.trim())
                OutlinedTextField(
                    value = editor.operatorNumeric, onValueChange = { editor = editor.copy(operatorNumeric = it) },
                    label = { Text("電信業者代碼（MCC＋MNC）") },
                    placeholder = { Text("45402、23410、310260") },
                    supportingText = { Text(if (valid) OperatorNumeric.parse(editor.operatorNumeric).let {
                        "MCC：${it.mcc}　MNC：${it.mnc}"
                    } else "請輸入 5 或 6 位半形數字，前導零會保留。") },
                    isError = editor.operatorNumeric.isNotEmpty() && !valid, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = canEdit, modifier = Modifier.fillMaxWidth(),
                )
                Text("Samsung 修改期間需保持 Nrfr 的 APN 維護服務運作。新增 APN 會使用原 SIM 的連線網路代碼；SIM 顯示代碼仍為輸入值。如有異常，請先還原原 SIM 代碼並核對行動數據。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 按鈕行
            ActionButtons(
                selectedSimCard = selectedSimCard,
                selectedCountryCode = editor.selectedCountryCode,
                isCustomCountryCode = editor.isCustomCountryCode,
                customCountryCode = editor.customCountryCode,
                selectedCarrier = editor.selectedCarrier,
                customCarrierName = editor.customCarrierName,
                enabled = canEdit,
                changeNumeric = editor.changeNumeric,
                operatorNumeric = editor.operatorNumeric,
                onReset = { simCard ->
                    editor = editor.copy(operationMessage = null)
                    isApplying = true
                    scope.launch {
                        try {
                            val result = CarrierConfigManager.resetCarrierConfig(context, simCard.subId)
                            val message = buildString {
                                append("SIM ${simCard.slot}：國家碼／名稱覆寫已提交清除。")
                                result.restoredNumeric?.let { append("\n原 SIM 電信業者代碼已回讀確認：$it") }
                                if (result.requiresRestart) append("\n請先核對 APN 選取與行動數據；原 SIM 代碼還原後通常不需重新啟動手機。")
                                result.warning?.let { append("\n$it") }
                            }
                            editor = SimEditorState(subId = simCard.subId, operationMessage = message)
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            editor = editor.copy(operationMessage = "SIM ${simCard.slot} 還原失敗：${error.message}")
                            Toast.makeText(context, editor.operationMessage, Toast.LENGTH_LONG).show()
                        } finally {
                            isApplying = false
                            refreshTrigger += 1
                        }
                    }
                },
                onSave = { simCard ->
                    val carrierName = if (editor.selectedCarrier?.name == "自訂") {
                        editor.customCarrierName.takeIf { it.isNotEmpty() }
                    } else {
                        editor.selectedCarrier?.displayName
                    }
                    val countryCode = if (editor.isCustomCountryCode) editor.customCountryCode else editor.selectedCountryCode
                    val chosenNumeric = if (editor.changeNumeric) editor.operatorNumeric.trim() else null
                    val settings = ApplySettings(simCard, countryCode, carrierName, chosenNumeric)
                    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true) && Build.VERSION.SDK_INT >= 37 &&
                        chosenNumeric != null && chosenNumeric != simCard.subscriptionOperator) {
                        pendingImsConfirmation = settings
                    } else submit(settings)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimCardSelector(
    simCards: List<SimCardInfo>,
    selectedSimCard: SimCardInfo?,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSimCardSelected: (SimCardInfo) -> Unit,
    enabled: Boolean,
) {
    ExposedDropdownMenuBox(
        expanded = isExpanded && enabled,
        onExpandedChange = { if (enabled) onExpandedChange(it) }
    ) {
        OutlinedTextField(
            value = selectedSimCard?.let { "SIM ${it.slot} (${it.carrierName})" } ?: "",
            onValueChange = {},
            enabled = enabled,
            readOnly = true,
            label = { Text("選擇 SIM 卡") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = isExpanded && enabled,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            simCards.forEach { simCard ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("SIM ${simCard.slot} (${simCard.carrierName})")
                            if (simCard.currentConfig.isEmpty()) {
                                Text(
                                    "無覆寫設定",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                simCard.currentConfig.forEach { (key, value) ->
                                    Text(
                                        "$key: $value",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        onSimCardSelected(simCard)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun CurrentConfigCard(simCard: SimCardInfo, canRefresh: Boolean, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "目前讀取值（SIM ${simCard.slot}）",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("SIM 電信業者代碼：${simCard.simOperator.ifEmpty { "未取得" }}")
            Text("SIM 國家碼：${simCard.simCountryIso.ifEmpty { "未取得" }}")
            Text("訂閱資料代碼：${simCard.subscriptionOperator.ifEmpty { "未取得" }}")
            Text("連線網路代碼（未修改）：${simCard.networkOperator.ifEmpty { "未連線／未取得" }}")
            Text("SIM、訂閱資料與連線網路是不同讀取來源；只核對已選擇修改的 SIM 讀取值。",
                style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 30) {
                val imsStatus = when (simCard.imsRegistrationState) {
                    RegistrationManager.REGISTRATION_STATE_REGISTERED -> when (simCard.imsRegistrationTransport) {
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> "已註冊（行動網路）"
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> "已註冊（Wi-Fi）"
                        else -> "已註冊"
                    }
                    RegistrationManager.REGISTRATION_STATE_REGISTERING -> "註冊中"
                    RegistrationManager.REGISTRATION_STATE_NOT_REGISTERED -> "未註冊"
                    else -> "無法讀取"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("IMS 通話：$imsStatus", modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefresh, enabled = canRefresh) { Text("重新讀取") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (simCard.currentConfig.isEmpty()) {
                Text(
                    "無覆寫設定",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                simCard.currentConfig.forEach { (key, value) ->
                    Text(
                        "$key: $value",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryCodeSelector(
    selectedCountryCode: String,
    isCustomCountryCode: Boolean,
    customCountryCode: String,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCountryCodeSelected: (String) -> Unit,
    onCustomSelected: () -> Unit,
    enabled: Boolean,
) {
    ExposedDropdownMenuBox(
        expanded = isExpanded && enabled,
        onExpandedChange = { if (enabled) onExpandedChange(it) }
    ) {
        OutlinedTextField(
            value = when {
                isCustomCountryCode -> "自訂"
                selectedCountryCode.isEmpty() -> ""
                else -> CountryPresets.countries.find { it.code == selectedCountryCode }
                    ?.let { "${it.name} (${it.code})" }
                    ?: selectedCountryCode
            },
            onValueChange = {},
            enabled = enabled,
            readOnly = true,
            label = { Text("選擇國家碼") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = isExpanded && enabled,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            // 預設國家碼列表
            CountryPresets.countries.forEach { countryInfo ->
                DropdownMenuItem(
                    text = { Text("${countryInfo.name} (${countryInfo.code})") },
                    onClick = {
                        onCountryCodeSelected(countryInfo.code)
                        onExpandedChange(false)
                    }
                )
            }
            // 自訂選項
            DropdownMenuItem(
                text = { Text("自訂") },
                onClick = {
                    onCustomSelected()
                    onExpandedChange(false)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomCountryCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text("自訂國家碼 (2位字母)") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
            }
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CarrierSelector(
    selectedCarrier: PresetCarriers.CarrierPreset?,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCarrierSelected: (PresetCarriers.CarrierPreset) -> Unit,
    enabled: Boolean,
) {
    OutlinedButton(onClick = { onExpandedChange(true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(selectedCarrier?.name ?: "選擇預設電信業者（可搜尋名稱、地區或代碼）")
    }
    if (isExpanded && enabled) {
        var query by remember { mutableStateOf("") }
        val results = PresetCarriers.presets.filter { item ->
            val region = CountryPresets.countries.find { it.code == item.region }?.name.orEmpty()
            listOf(item.name, item.displayName, item.region, region, item.operatorNumeric)
                .any { it.contains(query.trim(), ignoreCase = true) }
        }
        AlertDialog(onDismissRequest = { onExpandedChange(false) },
            title = { Text("預設電信業者") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text("搜尋名稱、地區或代碼") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (results.isEmpty()) Text("沒有符合的預設，可搜尋「自訂」後輸入名稱。")
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(results, key = { it.operatorNumeric.ifEmpty { "custom" } }) { item ->
                            TextButton(onClick = { onCarrierSelected(item); onExpandedChange(false) },
                                modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(item.name)
                                    if (item.region.isNotEmpty()) {
                                        val region = CountryPresets.countries.find { it.code == item.region }?.name.orEmpty()
                                        Text("$region · ${item.operatorNumeric}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onExpandedChange(false) }) { Text("關閉") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomCarrierNameInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text("自訂電信業者名稱") },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ActionButtons(
    selectedSimCard: SimCardInfo?,
    selectedCountryCode: String,
    isCustomCountryCode: Boolean,
    customCountryCode: String,
    selectedCarrier: PresetCarriers.CarrierPreset?,
    customCarrierName: String,
    enabled: Boolean,
    changeNumeric: Boolean,
    operatorNumeric: String,
    onReset: (SimCardInfo) -> Unit,
    onSave: (SimCardInfo) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 還原按鈕
        OutlinedButton(
            onClick = { selectedSimCard?.let(onReset) },
            modifier = Modifier.weight(1f),
            enabled = enabled && selectedSimCard != null
        ) {
            Text("還原設定")
        }

        // 儲存按鈕
        Button(
            onClick = { selectedSimCard?.let(onSave) },
            modifier = Modifier.weight(1f),
            enabled = enabled && selectedSimCard != null && (!changeNumeric || OperatorNumeric.isValid(operatorNumeric.trim())) && (
                    (isCustomCountryCode && customCountryCode.length == 2) ||
                            (!isCustomCountryCode && selectedCountryCode.isNotEmpty()) ||
                            (changeNumeric && OperatorNumeric.isValid(operatorNumeric.trim())) ||
                            (selectedCarrier != null && (selectedCarrier.name != "自訂" || customCarrierName.isNotEmpty()))
                    )
        ) {
            Text("套用設定")
        }
    }
}
