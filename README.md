# Nrfr — Android 17 相容版

透過 **Shizuku、免 root** 調整系統回報的 SIM 國家碼與電信業者名稱，支援各 SIM 分別套用及還原，提供正體中文介面。

由 **Nrfr 維護團隊** 維護，基於 [Ackites/Nrfr](https://github.com/Ackites/Nrfr)。

[下載正式版](https://github.com/alzpqm/Nrfr/releases/latest) · [回報問題](https://github.com/alzpqm/Nrfr/issues) · [原始碼](https://github.com/alzpqm/Nrfr)

## 版本狀態

**1.1.3 已撤回**：APN 相容處理可能造成清單消失，APN 存取失敗也可能阻擋代碼還原。請勿繼續使用此版修改 MCC／MNC。目前正式版為 [1.1.2](https://github.com/alzpqm/Nrfr/releases/tag/v1.1.2)，其代碼覆寫仍可能影響 APN，建議只調整國家碼與名稱。

[1.1.4-rc1 還原候選版](https://github.com/alzpqm/Nrfr/releases/tag/v1.1.4-rc1)將代碼還原與 APN 清理分開，並暫停新的 MCC／MNC 套用。候選版需要裝置驗證，尚未宣告 APN 問題已全面解決。詳見[更新說明](docs/releases/1.1.4-rc1.md)。

## 功能

- 所選 SIM 的國家碼及電信業者名稱調整與回讀。
- 雙卡草稿隔離；切換 SIM 會清空前一張卡的待套用內容。
- 分別顯示 SIM、訂閱資料與連線網路的電信業者代碼。
- 177 組業者預設、45 個國家／地區，可搜尋名稱、地區及代碼。
- 原代碼還原使用同一張 SIM 的本機備份；備份遺失時，嘗試使用有效的訂閱資料代碼並核對回讀。
- 舊 APN 相容項目只依本機回復紀錄清理，保留被外部修改的項目。
- Android App 與桌面快速啟動工具使用正體中文。

**新的 MCC／MNC 修改目前暫停**；業者預設中的代碼僅供查閱，選擇業者不會套用該代碼。連線網路、基地台及實體 SIM 內容不會被修改，也不保證其他 App 的地區或帳號行為改變。

## 使用

1. 安裝 APK，啟動 Shizuku 並授權 Nrfr。
2. 選擇 SIM，讀取目前設定。
3. 選擇國家碼及業者名稱，點選「套用設定」，核對操作結果。
4. 如需清除覆寫，選擇同一張 SIM，點選「還原設定」，依提示重新啟動手機並核對原代碼、APN 選取及行動數據。

1.1.4-rc1 沿用 1.1.1 起的固定發布簽章，可覆蓋安裝 1.1.2／1.1.3，保留本機備份；請勿為升級先解除安裝。1.0.4／1.1.0 測試 APK 的簽章不同，移除前須先還原並依提示重啟。

還原會先處理原 SIM 代碼與國家碼／名稱覆寫，再嘗試清理舊相容 APN。APN 權限、清單或選取異常會另列提示；原代碼只有在回讀一致時才標示已確認。沒有本機回復紀錄的項目不會自動刪除。

## 相容性與資料

Android 8 以上；代碼還原需要 Android 10 以上。Android 16／17 使用臨時覆寫，重啟或電信服務重載後可能需要重新套用國家碼及名稱。不同系統與廠商的限制可能不同。

還原可能清除其他工具對同一張 SIM 的 CarrierConfig 覆寫，使用 IMS 工具前請核對其設定。解除安裝不會自動還原系統設定，且會刪除本機備份及 APN 回復紀錄。

備份以 SIM 識別碼的雜湊綁定，保存在裝置上，不參與雲端備份或裝置移轉。App 不上傳 SIM 或 APN 資料；舊相容 APN 的匹配資訊可能仍保存在系統資料庫。回報問題時僅需提供型號、系統版本與錯誤文字，請勿公開 SIM 識別碼、電話號碼、APN 帳號或密碼。

[代碼與還原說明](docs/mcc-mnc.md) · [Android 17 相容方式](docs/android-17-compatibility.md) · [發布簽章](docs/release-signing.md)

## 建置與授權

使用 JDK 17、Android SDK Platform 37.2、Build Tools 37.0.0 及 Gradle 9.6.0：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

`nrfr-client/frontend` 使用 `npm ci` 及 `npm run build`。自動化檢查無法取代裝置上的 APN 保存、選取、行動數據與還原驗證。

本專案沿用 [GPL-3.0](LICENSE) 授權。感謝原作者及上游貢獻者；Android 17 權限委派方式參考 [carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel)。
