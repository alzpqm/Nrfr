# Nrfr 快速啟動工具

由 Nrfr 維護團隊維護，介面與錯誤訊息使用正體中文，協助透過 USB／ADB 安裝與啟動 Shizuku 和 Nrfr。電信業者設定在手機 App 內完成。

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

Releases 提供 Android APK。桌面工具需自行建置。
