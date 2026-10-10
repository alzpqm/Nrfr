# Nrfr — Android 17 相容版

透過 **Shizuku、免 root** 調整系統回報的 SIM 國家碼、電信業者名稱及 MCC／MNC，支援各 SIM 分別套用及還原，提供正體中文介面。

由 **Nrfr 維護團隊** 維護，基於 [Ackites/Nrfr](https://github.com/Ackites/Nrfr)。

[下載正式版](https://github.com/alzpqm/Nrfr/releases/latest) · [回報問題](https://github.com/alzpqm/Nrfr/issues) · [原始碼](https://github.com/alzpqm/Nrfr)

## 版本狀態

目前正式版為 [1.1.4](https://github.com/alzpqm/Nrfr/releases/tag/v1.1.4)，恢復 1.1.2 的 MCC／MNC 提交方式，將原代碼還原與舊 APN 清理分開，不建立相容 APN。詳見[更新說明](docs/releases/1.1.4.md)。

**1.1.3 已撤回**，請升級。MCC／MNC 覆寫仍可能使 APN 清單消失、新建項目無法保存或選取，並影響行動數據；1.1.4 未宣告此相容問題已全面解決。

## 功能

- 所選 SIM 的國家碼及電信業者名稱調整與回讀。
- 自訂 5／6 位 MCC＋MNC，保留前導零，提交後核對 SIM 代碼回讀。
- 雙卡草稿隔離；切換 SIM 會清空前一張卡的待套用內容。
- 分別顯示 SIM、訂閱資料與連線網路的電信業者代碼。
- 177 組業者預設、45 個國家／地區，可搜尋名稱、地區及代碼。
- 原代碼還原使用同一張 SIM 的本機備份；備份遺失時，嘗試使用有效的訂閱資料代碼並核對回讀。
- 舊 APN 相容項目只依本機回復紀錄清理，保留被外部修改的項目。
- Android App 與桌面快速啟動工具使用正體中文。

選擇業者會填入其預設代碼，只有勾選「修改 SIM 電信業者代碼」才提交 MCC／MNC。連線網路、基地台及實體 SIM 內容不會被修改；其他 App 的讀取結果取決於其使用的資料來源。

## 使用

1. 安裝 APK，啟動 Shizuku 並授權 Nrfr。
2. 選擇 SIM，讀取目前設定。
3. 選擇國家碼及業者名稱；若需修改代碼，勾選「修改 SIM 電信業者代碼」並輸入 5／6 位代碼。點選「套用設定」，核對操作結果、APN 與行動數據。
4. 如需清除覆寫，選擇同一張 SIM，點選「還原設定」，依提示重新啟動手機並核對原代碼、APN 選取及行動數據。

1.1.4 沿用 1.1.1 起的固定發布簽章，可覆蓋安裝 1.1.1／1.1.2／1.1.3／1.1.4 候選版，保留本機備份；請勿為升級先解除安裝。1.0.4／1.1.0 測試 APK 的簽章不同，移除前須先還原並依提示重啟。

還原會先處理原 SIM 代碼與國家碼／名稱覆寫，再嘗試清理舊相容 APN。APN 權限、清單或選取異常會另列提示；原代碼只有在回讀一致時才標示已確認。沒有本機回復紀錄的項目不會自動刪除。

## 相容性與資料

Android 8 以上；代碼修改與還原需要 Android 10 以上。Android 16／17 使用臨時覆寫，重啟或電信服務重載後可能需要重新套用。不同系統與廠商的限制可能不同。

還原可能清除其他工具對同一張 SIM 的 CarrierConfig 覆寫，使用 IMS 工具前請核對其設定。解除安裝不會自動還原系統設定，且會刪除本機備份及 APN 回復紀錄。

備份以 SIM 識別碼的雜湊綁定，保存在裝置上，不參與雲端備份或裝置移轉。App 不上傳 SIM 或 APN 資料；舊相容 APN 的匹配資訊可能仍保存在系統資料庫。回報問題時僅需提供型號、系統版本與錯誤文字，請勿公開 SIM 識別碼、電話號碼、APN 帳號或密碼。

[代碼與還原說明](docs/mcc-mnc.md) · [Android 17 相容方式](docs/android-17-compatibility.md) · [發布簽章](docs/release-signing.md)

## 建置與授權

使用 JDK 17、Android SDK Platform 37.2、Build Tools 37.0.0 及 Gradle 9.6.0：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

`nrfr-client/frontend` 使用 `npm ci` 及 `npm run build`。自動化檢查無法取代裝置上的 APN 保存、選取、行動數據與還原驗證。

本專案沿用 [Apache License 2.0](LICENSE) 授權。感謝原作者及上游貢獻者；Android 17 權限委派方式參考 [carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel)。
