# Nrfr — Android 17 相容版

透過 **Shizuku、免 root** 調整系統回報的 SIM 國家碼與電信商顯示名稱，支援各 SIM 分別套用及還原。

本專案由 **Nrfr 維護團隊** 維護，基於 [Ackites/Nrfr](https://github.com/Ackites/Nrfr)。本 fork 聚焦於較新 Android 系統的套用相容性、正確的成功／失敗回報與標準 SDK 建置。

[下載 APK](https://github.com/alzpqm/Nrfr/releases) · [回報問題](https://github.com/alzpqm/Nrfr/issues) · [原始碼](https://github.com/alzpqm/Nrfr)

## 這個版本做了什麼

- 修正較新系統直接以 shell 身分寫入時出現的 `overrideConfig cannot be invoked by shell`。
- 使用 Shizuku 臨時委派必要的電話權限，以應用自己的 UID 寫入；操作結束後釋放權限。
- 套用後回讀 CarrierConfig，並核對系統回報的 SIM 國家碼，再顯示成功。
- 透過有效的活動訂閱辨識實體 SIM／eSIM，拒絕已移除或無效的 SIM。
- Android 16／17 使用非持久覆寫，重啟、換卡或系統重載後可能需要重新套用。
- 更新至 Android 17 SDK，使用標準 SDK 建置，無需替換 `android.jar`。

最低安裝版本為 Android 8（API 26），這次相容性修復主要針對 Android 16／17。編譯與單元測試不等於真機驗證；不同手機與系統 build 的實際效果仍需確認。

## 開始使用

1. 從[此 fork 的 Releases](https://github.com/alzpqm/Nrfr/releases) 下載 APK。
2. 安裝並啟動 [Shizuku](https://shizuku.rikka.app/)，開啟 Nrfr 後授權。
3. 選擇需要調整的 SIM，設定兩位英文字母國家碼，例如 `US` 或 `JP`；可同時設定電信商名稱。
4. 點選「保存生效」，等待系統回讀完成，再確認目前設定。
5. 如需還原，選擇同一張 SIM，點選「還原設定」。

若安裝器提示 APK 簽章不一致，請先移除舊版再安裝。移除 App 不會自動還原系統的 CarrierConfig 覆寫。

這個工具修改的是 **Android 的電信商設定覆寫**，不會改寫 SIM 本身的 MCC／MNC。套用成功也不代表特定第三方 App 一定會接受該地區設定。

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

`hidden-api` 只提供編譯用的框架接口簽名，不會封裝進 APK。桌面快速啟動工具的原始碼保留於 `nrfr-client/`，其介紹及專案入口也指向此 fork。

## 遇到問題

請在[此 fork 的 Issues](https://github.com/alzpqm/Nrfr/issues) 附上：

- 手機型號及完整 Android build。
- Nrfr 與 Shizuku 版本。
- 套用或還原時的錯誤文字，以及使用實體 SIM／eSIM、單卡或雙卡。
- 是否能讀到新的設定、重啟後是否還保留。

可透過 `adb logcat -s NrfrCarrierConfig` 取得操作異常。實作細節與真機測試步驟見 [Android 17 相容性說明](docs/android-17-compatibility.md)。

## 來源與授權

- [Ackites/Nrfr](https://github.com/Ackites/Nrfr)：原始專案，由 Antkites 開發。
- [ryfineZ/carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel)：本次 instrumentation 權限委派的實作參考。
- [Shizuku](https://shizuku.rikka.app/) 與 [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)：權限橋接與框架介面存取。

採用 [Apache License 2.0](LICENSE)，保留原始作者的授權與歸屬。
