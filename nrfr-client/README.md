# Nrfr 快速啟動工具

此 fork 的桌面工具介面與錯誤訊息使用正體中文，協助透過 USB／ADB 安裝與啟動 Shizuku 和 Nrfr。電信業者設定在手機 App 內完成。

- [專案首頁](https://github.com/alzpqm/Nrfr)
- [Android APK](https://github.com/alzpqm/Nrfr/releases)
- [功能與使用說明](../README.md)

桌面工具以 Wails、Go、React 與 TypeScript 建置。請依 [Wails 官方安裝說明](https://wails.io/docs/gettingstarted/installation)備妥 Go、Node.js 與平台編譯工具，再執行：

```sh
cd frontend
npm ci
npm run build
cd ..
wails build
```

本次 Android 預發布提供手機 APK；桌面前端由 GitHub Actions 編譯驗證，未將該檢查當作桌面安裝程式的完整驗證。
