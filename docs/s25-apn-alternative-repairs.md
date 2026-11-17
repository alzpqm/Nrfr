# S25 APN 替代修法研究

本文件是離線研究設計，沒有新增真機驗證，也不代表問題已修復。
已完成的 S25 實測見 [調查紀錄](s25-apn-investigation.md)。

## 原因追查的新線索

AOSP 的 `IccRecords.setCarrierTestOverride` 會設定 operator numeric 屬性；
`SIMRecords.getOperatorNumeric` 卻從 `getIMSI()` 的前綴推導 numeric。
假 IMSI 參數為 null 時，`getIMSI()` 回傳原值。
`GsmCdmaPhone.updateCurrentCarrierInProvider` 使用後一路徑更新 APN current。
因此，只改 MNC 屬性就可能產生兩個不同的查詢來源，並不需要曾經修改 IMSI。
這與 S25 的原代碼寫回現象相符；尚未取得三星電話服務的實際呼叫鏈證據。
僅同步 subscription 資料庫的 numeric，沒有足夠依據可消除這個差異。
來源：[IccRecords](https://android.googlesource.com/platform/frameworks/opt/telephony/+/master/src/java/com/android/internal/telephony/uicc/IccRecords.java)、
[SIMRecords](https://android.googlesource.com/platform/frameworks/opt/telephony/+/master/src/java/com/android/internal/telephony/uicc/SIMRecords.java)、
[GsmCdmaPhone](https://android.googlesource.com/platform/frameworks/opt/telephony/+/master/src/java/com/android/internal/telephony/GsmCdmaPhone.java)。

已保存的三星 TelephonyProvider 中，
`getSubscriptionMatchingAPNListSynchronized` 的初步資格條件包含：

```text
(row.numeric == lookupNumeric OR row.carrier_id == simCarrierId)
AND row 未標記刪除
AND 呼叫者的 selection
```

三星 Settings 的 selection 另含該槽 `current = 1` 或 `current1 = 1`。
初步符合後，仍有 MNO／MVNO 分組與結果合併，必須查最終游標。
上述條件只提供替代假設，不能單憑 SQL 推導宣稱畫面或數據可用。

特別注意：這段 provider 使用 `getSimCarrierId()`；目前 Nrfr 診斷摘要記錄的是
`simSpecificCarrierId`。後續診斷必須分別標示兩者，不能拿其中一個代替另一個。

## 候選方案與取捨

| 方案 | 可取之處 | 尚須證明／限制 |
| --- | --- | --- |
| 保留原 APN/current/preferred，利用既有 carrier_id 關聯 | 若原列已正確關聯，無須修改 APN 列；合成 MNC 與原數據參數可能共存 | 原列須進入 Settings 與 modem 各自的最終結果，且重算後仍成立；目前未測 |
| 對原列補 carrier_id 關聯 | 不複製 APN、不改 numeric 或連線參數 | 列可能雙卡共用；provider 部分更新路徑自動補 edited 並用 REPLACE。必須先排除衝突與其他列被替換，才值得有限實驗 |
| 管理 APN 前暫時還原原 MNC，完成後明確重套 | 可以沿用已驗證的無重啟還原流程，恢復系統編輯入口 | 只是操作上的替代方案；編輯期間合成 MNC 暫停，重套後的數據與清單仍須另驗 |
| 在 Nrfr 提供 APN 編輯介面 | 可明確選擇訂閱與項目，呈現權限失敗 | 無法單靠自製清單修復系統的 modem 選擇與 Samsung Settings，不能替代本 bug 的驗收 |

不採納一次性或背景反覆重寫 current 作為持久修復：S25 已證實一次性更新
在 APN 刪除後失效；背景 observer 只能事後反應，尚無消除競態的證據。
對其他 App 注入合成 MNC，也沒有已驗證可用的通用 Nightzuku 介面。

## 下一次有裝置時的最小實驗

1. 先在委派權限下，唯讀取得兩卡的原 APN、原選取、每槽 current、
   provider 實際使用的 simCarrierId，以及 specificCarrierId。
   權限拒絕、null cursor 與不支援欄位要分別回報。
2. 找出已經綁定正確 simCarrierId 的原列。若沒有，這次零寫入方案沒有
   可測樣本，不能把未命中當作整個方案的反證。
3. 保留原 current/preferred，僅套用原本的 MNC 覆寫。
   用 Settings 相同 URI、訂閱與 selection 比對最終列集合；
   同時檢查供數據服務使用的 filtered 清單與實際 preferred/IA 選擇。
4. 若明確符合 carrier_id、current、未刪除條件的原列仍不在最終游標，
   即否證「既有關聯足以保留這筆 APN」；往 MVNO 優先或額外篩選追查。
   若能保留，再驗證 APN 儲存、刪除、自動重算、雙卡隔離與原值還原。
5. 服務保全以實際 baseline 為準，包含原本存在的 default、IA、IMS、MMS。
   不要求原本沒有的 APN 類型，也不能只用 default 清單非空判定成功。
   最後必須在指定行動網路做 DNS／HTTPS，排除 Wi-Fi 與另一張卡。

只有零寫入方案不能成立，且能排除共用列、唯一鍵衝突與不可完整還原時，
才考慮原列 metadata 的有限實驗。`applyBatch` 的交易性不能代替提交後的
精確復原；只備份被修改列，無法救回 REPLACE 可能移除的其他列。

保留 preferred 本身不足以證明初始連線正常。AOSP DataProfileManager
按設定的類型順序挑選 initial attach profile，並在同次候選排序中優先 preferred；
因此還要排除合成業者的其他 APN 被送到 modem。
來源：[DataProfileManager](https://android.googlesource.com/platform/frameworks/opt/telephony/+/master/src/java/com/android/internal/telephony/data/DataProfileManager.java)。

## 模型交叉檢查

GPT-6 Astra 提供獨立替代方案；DeepSeek Flash 使用同一個原生長 session
持續討論，由主代理逐輪提供新來源、反例與修正。採納了 carrier_id 零寫入
可行性檢查、最終游標核對、雙卡共用列及其他服務的保全條件。
沒有採納 phonebook API 作為身分修改介面、把任一布林條件失敗等同整體失敗、
把 REPLACE 必然改變本列 ID、或把未驗證的跨 App 注入當作可交付方法。

Gemini／Claude 本輪沒有新的推理意見。本機協作技能明示目前個人方案
不支援第三方代理包裝，Claude 舊 selector
另有已到期 guard。原生 session 回執、usage 與失敗原因保存在本機私有研究目錄。
