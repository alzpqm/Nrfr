# 正式版簽章與發布

1.1.1 起使用固定的 RSA 發布金鑰，簽章 alias 為 `nrfr-release`。發布的是 R8／資源縮減的 release APK，非 debug APK。

正式版憑證 SHA-256：`64f5cde8ff7b2e5a1496941249c9c0c355744936fb6b11f19bbc65ac3830e469`。這是公開的憑證指紋，不包含私鑰；後續更新應使用相同憑證。

測試版 1.0.4／1.1.0 的 APK 使用各次 CI 產生的 debug 金鑰，無法沿用為穩定更新簽章。使用者首次改裝正式版須先還原、依提示重啟，再移除測試版；後續正式版須使用同一把發布金鑰。

## 金鑰備份

金鑰與密碼不放在 Git、公開 Release 或一般建置 artifact。維護者須私下保存 `nrfr-release.p12`、alias 及 store／key 密碼備份；未來建置應沿用這份備份，不重新產生金鑰。

## GitHub Actions

在此 fork 設定三個 Actions secrets：

| Secret | 內容 |
| --- | --- |
| `NRFR_RELEASE_KEYSTORE_BASE64` | 固定 `nrfr-release.p12` 的單行 Base64 |
| `NRFR_RELEASE_STORE_PASSWORD` | keystore 密碼 |
| `NRFR_RELEASE_KEY_PASSWORD` | `nrfr-release` 私鑰密碼 |

確認版本號、versionCode、桌面工具版本及 `docs/releases/<版本>.md` 已更新，再從 master 手動執行 `Publish signed Android release`。

工作流程先完成 Android 單元測試、Lint、debug／release 與前端建置，再簽署 APK、核對簽章並產生 `SHA256SUMS`。金鑰只解碼到臨時目錄，步驟結束後刪除。發布正式 Release 時不標記 prerelease；已發布的版本不覆蓋。

一般 push／PR 只執行檢查並提供 unsigned release／debug 建置 artifact，不自動發布測試版，也不使用發布金鑰。

## 本機發布

若 CI 尚未設定 secrets，可在維護者環境完成同樣的檢查後，以 SDK Build Tools 37.0.0 的 `zipalign -P 16` 與 `apksigner` 使用固定金鑰簽署 `app-release-unsigned.apk`，然後上傳簽署後的 APK、`SHA256SUMS` 與對應更新說明。

發布前使用 `apksigner verify --verbose --print-certs`、`zipalign -c -P 16 -v 4` 與 `aapt dump badging` 核對簽章、對齊、版本及 SDK。不要把密碼直接寫進命令參數或 shell 歷史。
