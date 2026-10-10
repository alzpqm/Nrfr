package com.github.nrfr.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("關於 Nrfr") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Nrfr · Android 17 相容版", style = MaterialTheme.typography.titleMedium)
                    Text("透過 Shizuku、免 root 調整系統回報的 SIM 國家碼、電信業者名稱及 MCC／MNC，支援分別套用與還原。")
                    Text("套用後會核對系統回報的設定。Android 16／17 使用臨時覆寫，重啟或更換 SIM 後可能需要重新套用。")
                    Text("MCC／MNC 覆寫可能使 APN 消失、無法保存或選取，並影響行動數據；還原原代碼不依賴 APN 存取。不改寫實體 SIM、連線網路或基地台代碼，也不保證其他 App 的地區行為改變。")
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Nrfr 維護團隊", style = MaterialTheme.typography.titleMedium)
                    AboutLink("專案首頁", "https://github.com/alzpqm/Nrfr")
                    AboutLink("下載版本", "https://github.com/alzpqm/Nrfr/releases")
                    AboutLink("回報問題", "https://github.com/alzpqm/Nrfr/issues")
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("開源來源", style = MaterialTheme.typography.titleMedium)
                    Text("基於 Antkites 的 Nrfr，保留原始授權與作者歸屬。")
                    AboutLink("原始專案：Ackites/Nrfr", "https://github.com/Ackites/Nrfr")
                    AboutLink("實作參考：carrier-ims-for-pixel", "https://github.com/ryfineZ/carrier-ims-for-pixel")
                    AboutLink("Apache License 2.0", "https://github.com/alzpqm/Nrfr/blob/master/LICENSE")
                }
            }
            Text(
                "原始程式 © 2024 Antkites · Apache License 2.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutLink(label: String, url: String) {
    val context = LocalContext.current
    TextButton(onClick = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }) {
        Text(label)
    }
}
