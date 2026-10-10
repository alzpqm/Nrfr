# Nrfr — Android 17 相容版

透過 **Shizuku、免 root** 調整系統回報的 SIM 國家碼、電信業者名稱與 MCC／MNC 代碼，支援各 SIM 分別套用及還原。

由 **Nrfr 維護團隊** 維護，基於 [Ackites/Nrfr](https://github.com/Ackites/Nrfr)，提供正體中文介面與較新 Android 系統的相容支援。

[下載 APK](https://github.com/alzpqm/Nrfr/releases) · [回報問題](https://github.com/alzpqm/Nrfr/issues) · [原始碼](https://github.com/alzpqm/Nrfr)

## 1.1.3 正式版

- 修正修改 MCC／MNC 後，原 SIM 的 APN 清單變空、無法選取的問題。
- 保留原 APN 及預設選取，建立只匹配所選 SIM 的 APN 相容項目；套用失敗時回復原代碼與 APN。
- 套用及還原會核對 APN 清單與選取值。沿用固定發布簽章，可直接更新 1.1.1／1.1.2 正式版。

[下載正式版](https://github.com/alzpqm/Nrfr/releases/latest) · [更新說明](docs/releases/1.1.3.md)

## 主要功能

- 修正雙卡切換時沿用上一張 SIM 的國家碼、業者與 MCC／MNC 輸入；切換卡片會清空待套用草稿。
- 分別標示「目前讀取值」與「待套用設定」，操作結果列出所選 SIM 及已確認的欄位。
- 同一張卡重新讀取會保留待套用內容；還原後會清空草稿及自訂模式。
- SIM 代碼回讀成功會明示「連線網路／基地台 MCC／MNC 未修改」，避免誤認所有 App 都會變更。
- 自訂 5／6 位電信業者代碼，保留 MNC 前導零。
- 套用前備份所選 SIM 當時的代碼與名稱，還原時核對回讀值。
- 修改代碼時保留原 SIM 的 APN 參數與選取值，還原時清理未被外部修改的相容項目。
- 提供 **177 組代碼、45 個國家／地區**預設，可搜尋名稱、地區及代碼。
- Android App 與桌面快速啟動工具完整改用正體中文。
- 顯示 SIM 讀取值、訂閱資料及連線網路代碼，方便確認目標 App 讀取的來源。

- 正式 APK 使用固定發布簽章及 R8 release 建置。

最低安裝版本為 Android 8（API 26）；SIM 代碼覆寫需要 Android 10 以上。Android 16／17 使用臨時覆寫，重啟、換卡或系統重載後可能需要重新套用。功能可用性會因裝置與系統版本而異。

## 開始使用

1. 從[此 fork 的 Releases](https://github.com/alzpqm/Nrfr/releases) 下載 APK。
2. 安裝並啟動 [Shizuku](https://shizuku.rikka.app/)，開啟 Nrfr 後授權。
3. 選擇需要調整的 SIM，設定兩位英文字母國家碼，例如 `US` 或 `JP`；可同時設定電信業者名稱。也可選擇預設電信業者自動填入欄位。切換 SIM 會清空草稿，請為新選擇的卡重新輸入。
4. 若要修改代碼，勾選「修改 SIM 電信業者代碼」，輸入 5／6 位 MCC＋MNC。App 會先讀取原 SIM 的 APN，再建立相容項目；系統會重載電信設定，過程中可能短暫重新連線。
5. 點選「套用設定」，等待系統回讀完成，再確認「目前讀取值」與「上次操作結果」。待套用輸入不代表目前已生效的值。
6. 如需還原，選擇同一張 SIM，點選「還原設定」。

若舊版修改代碼後 APN 清單已變空，更新到 1.1.3 後重新套用代碼即可執行 APN 保留流程；也可選擇「還原設定」回復原碼與 APN。完成後請在系統設定核對 APN，並確認行動數據能連線。

相容 APN 的名稱會加上「（Nrfr）」，原 APN 不會被改寫。APN 存取權限或 SIM 綁定不受系統支援時，App 會拒絕套用代碼；未確認 APN 回讀時不會回報成功。若相容項目已被手動或其他工具修改，還原會保留該項目並提示核對，避免刪除外部修改。

從 1.0.4／1.1.0 測試 APK 升級到正式版時，請先在舊 App 還原設定，依提示重啟手機，再移除測試版並安裝正式 APK。兩者簽章不同，不能直接覆蓋安裝；後續正式版沿用固定簽章。移除 App 不會自動還原系統的 CarrierConfig 覆寫，也會刪除本機代碼備份。

代碼覆寫的目標是 **Android SIM 讀取介面**（`TelephonyManager.getSimOperator()`），不會改寫實體 SIM 內容或基地台註冊網路。其他 App 若讀取訂閱資料、連線網路、IMSI、快取或伺服器資料，可能仍顯示原值；套用成功不代表任何特定 App 都會接受該地區設定。

目前連線網路與基地台的 MCC／MNC 不在本功能的修改範圍內。詳見[讀取來源與限制](docs/mcc-mnc.md)。

首次修改代碼時，備份的是此 App 當時讀到的值；若先前已由其他工具覆寫，還原會回到該值。系統未提供完整清除介面時，App 會先回復代碼，再提示重啟手機結束測試覆寫狀態。解除安裝會失去本機備份，請先還原或重啟手機。

預設清單包含相容代碼及已合併業者的舊代碼，不代表所有條目目前都在營運。詳見[代碼覆寫與預設清單](docs/mcc-mnc.md)。

「還原設定」會清除所選 SIM 的 CarrierConfig 覆寫，也可能清除其他工具對同一張 SIM 設定的覆寫。若系統只允許清除臨時設定，App 會提示你核對重啟後的結果。

## 建置

| 項目 | 版本 |
| --- | --- |
| Compile SDK | Android 17，API 37.2 |
| Target SDK | API 37 |
| Minimum SDK | API 26 |
| SDK Build Tools | 37.0.0 |
| Android Gradle Plugin | 9.4.1 |
| Gradle Wrapper | 9.6.0 |
| Kotlin／Compose 編譯插件 | 2.2.10（AGP 內建 Kotlin） |
| JDK | 17 或以上 |

在專案根目錄執行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

可安裝的 debug APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。本機 release 建置預設未簽章，需自行簽章後安裝。

正式發布使用固定發布金鑰。GitHub Actions 的 `Publish signed Android release` 需設定簽章 secrets；沒有金鑰時不會改用 debug 簽章。詳見[正式版簽章與發布](docs/release-signing.md)。

`hidden-api` 只提供編譯用的框架接口簽名，不會封裝進 APK。桌面快速啟動工具的原始碼保留於 `nrfr-client/`，其介紹及專案入口也指向此 fork。

## 遇到問題

請在[此 fork 的 Issues](https://github.com/alzpqm/Nrfr/issues) 附上：

- 手機型號及 Android 版本／build。
- Nrfr 與 Shizuku 版本。
- 套用或還原時的錯誤文字，以及使用實體 SIM／eSIM、單卡或雙卡。
- 是否能讀到新的設定、重啟後是否還保留。

分享錯誤文字、截圖或紀錄前，請遮蔽電話號碼、ICCID、IMSI、裝置序號、帳號及其他個人資料。請勿上傳完整 SIM 資料或未整理的完整 logcat。

實作細節與驗證方式見 [Android 17 相容性說明](docs/android-17-compatibility.md)。

## 來源與授權

- [Ackites/Nrfr](https://github.com/Ackites/Nrfr)：原始專案，由 Antkites 開發。
- [ryfineZ/carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel)：本次 instrumentation 權限委派的實作參考。
- [Shizuku](https://shizuku.rikka.app/) 與 [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)：權限橋接與框架介面存取。

採用 [Apache License 2.0](LICENSE)，保留原始作者的授權與歸屬。
