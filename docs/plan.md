# newvideoplayer 開發規劃

參考：XPlayer「Video Player All Format」(InShot, video.player.videoplayer)。功能比照，名稱、圖示、介面素材自己設計。

## 技術選型
- 語言/UI：Kotlin + Jetpack Compose (Material 3)，minSdk 26，targetSdk 35
- 播放核心：AndroidX Media3 (ExoPlayer) + Media3 FFmpeg decoder extension（補 AVI/WMV/RMVB/FLV 等格式音訊與影像）；備案 libVLC
- 架構：MVVM + Kotlin Coroutines/Flow，Hilt 依賴注入
- 資料：MediaStore 掃描影片；Room 存播放進度、播放清單、私密資料夾索引；DataStore 存設定
- 投放：Media3 Cast (Google Cast SDK)
- 私密資料夾：Jetpack Security (EncryptedFile) + BiometricPrompt / PIN
- 字幕：Media3 內建 SRT/ASS/VTT；線上字幕下載用 OpenSubtitles API

## 功能分期
### M1 基本可用（約 2 週）
- 權限 (READ_MEDIA_VIDEO)、自動掃描手機與 SD 卡影片
- 資料夾 / 全部影片列表、縮圖、排序、搜尋
- 全螢幕播放：播放/暫停、進度條、上一部/下一部
- 手勢：左滑亮度、右滑音量、水平拖動進度、雙擊快轉/倒退
- 記住播放位置
### M2 進階播放（約 2 週）
- 倍速 0.25x–8.0x、畫面比例、自動旋轉、螢幕鎖、快速靜音
- 字幕：外掛字幕載入、樣式、時間偏移；多音軌切換
- 硬體加速 / 軟體解碼切換、4K 測試
- 夜間模式（深色主題 + 亮度濾鏡）
### M3 多工（約 1–2 週）
- 子母畫面 (PiP)、背景播放（MediaSessionService + 通知控制）
- 浮動視窗（SYSTEM_ALERT_WINDOW）
- 播放清單、最近播放
### M4 私密與管理（約 1–2 週）
- 私密資料夾：PIN/指紋解鎖、加密搬移與還原
- 影片重新命名、刪除、分享、詳細資訊
### M5 投放與擴充（約 2 週）
- Chromecast 投放
- 線上字幕下載、AI 字幕（Whisper 類模型，可選）
- 影片轉 MP3（FFmpeg）

不做：內建瀏覽器/影片下載器（版權與上架政策風險高）。廣告與內購最後再決定。

## Repo 結構
- app/ ： UI、導覽
- core/player ： Media3 封裝、手勢、PiP/背景服務
- core/media ： MediaStore 掃描、Room
- feature/library, feature/player, feature/vault, feature/settings
- .github/workflows/android.yml ： CI 建置 + 單元測試

## 下一步
1. 安裝 Hark GitHub App 到 newvideoplayer，讓我能推程式碼
2. 建立 Android 專案骨架 + CI，開 M1 的 issues
