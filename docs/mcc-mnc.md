# MCC／MNC 代碼覆寫

SIM 電信業者代碼由 3 位 MCC 與 2／3 位 MNC 組成。本功能支援 5／6 位半形數字，使用字串保存，保留 MNC 前導零；需要 Android 10 以上與 Shizuku 授權。

## 套用與回讀

選擇 SIM、勾選「修改 SIM 電信業者代碼」，再輸入代碼或選擇預設業者。套用後會核對系統的 SIM 讀取值；未能確認一致時會顯示失敗或未確認生效，並保留修改前備份。

主畫面分別顯示「目前讀取值」、「待套用設定」與「上次操作結果」。切換 SIM 會清空草稿及前一張卡的操作結果；重新讀取同一張 SIM 會保留尚未套用的輸入。

## 讀取來源與限制

| 介面／來源 | 本功能的效果 |
| --- | --- |
| SIM 電信業者代碼 `TelephonyManager.getSimOperator()` | 修改並核對的值 |
| 訂閱資料 `SubscriptionInfo.mccString/mncString` | 可能保留原碼 |
| 目前連線網路 `TelephonyManager.getNetworkOperator()` | 不修改 |
| 基地台、IMSI、ICCID、實體 SIM 內容 | 不修改 |
| 其他 App 的快取、帳號或伺服器地區 | 不保證改變 |

SIM 代碼回讀成功只確認 SIM 讀取介面已回報設定值，不代表實際註冊網路已改變。其他 App 使用不同資料來源時，仍可能顯示原碼。多 SIM 裝置亦需確認目標 App 使用的 SIM。

代碼覆寫不會變更真實電信合約、基地台或行動網路。套用可能觸發電信設定重載；完成後應確認通話及行動數據正常。

## 備份、還原與資料保存

首次修改前，App 會在本機保存當時讀到的 SIM 代碼與名稱；後續套用不覆蓋這份備份。若當時已有其他工具的覆寫，還原會回到備份中的值。

備份以 ICCID 雜湊綁定 SIM，不保存或記錄原始 ICCID、IMSI。無法確認 SIM 身分時會拒絕代碼寫入。備份不參與雲端備份或裝置移轉。

還原只使用所選 SIM 對應的備份，並核對回讀值。系統未提供完整清除介面時，會先回復原碼與名稱，再提示重啟手機；回讀失敗時不刪除備份。

解除安裝會刪除本機備份，且不會自動還原系統設定，請先還原並依提示重啟。Android 16／17 使用臨時覆寫，重啟、換卡或電信服務重載後可能需要重新套用。

## 預設清單

提供 177 組不重複代碼，涵蓋 45 個國家／地區，可搜尋業者名稱、地區、ISO 國家碼或數字。名稱與代碼亦可自行輸入。

預設包含常見及相容性代碼，不是全球現行業者名錄。部分代碼屬於已合併業者或相容用途，營運狀態與代碼分配應以各地主管機關為準。

## 開發者資訊

透過 Shizuku 臨時委派必要電話權限，以 App UID 呼叫 `setCarrierTestOverride`；依裝置介面支援 8 或 10 個參數。只傳入代碼與名稱，其他識別欄位保留真值，不注入 APN 或電信權限規則。

以所選訂閱的 `TelephonyManager.getSimOperator()` 回讀，最多等待 5 秒。單獨套用代碼不寫入空的 CarrierConfig 覆寫，保留既有國家碼與名稱設定；操作結束後釋放委派權限。

- [Android TelephonyManager](https://developer.android.com/reference/android/telephony/TelephonyManager)：SIM 與連線網路讀取介面。
- [ITU E.212](https://www.itu.int/rec/T-REC-E.212) 與 [MCC／MNC 公開資料表](https://github.com/musalbas/mcc-mnc-table)：編碼與預設清單參考。
