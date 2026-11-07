# NewVideoPlayer

Android 影片播放器，支援多種格式、手勢控制、記住播放位置。開發規劃見 [docs/plan.md](docs/plan.md)。

## M1 功能（目前）
- 自動掃描手機與 SD 卡上的影片，依資料夾分類，可搜尋
- 影片縮圖與長度
- 全螢幕播放（Media3 ExoPlayer）
- 手勢：左側上下滑調亮度、右側上下滑調音量、左右滑動調進度、雙擊左/右快退/快進 10 秒、雙擊中間暫停
- 記住每部影片的播放位置

## M2
- 倍速 0.25x–8x、畫面比例、螢幕鎖、靜音、旋轉、夜間模式
- 外掛字幕（SRT/ASS/VTT/TTML）、字幕與音軌切換、硬體/軟體解碼

## M3
- 子母畫面（按主畫面鍵自動進入，可在小視窗暫停/切換上下部）
- 背景播放，通知列與鎖定畫面控制
- 浮動視窗：拖曳移動、雙指縮放、雙擊回到 app
- 播放清單（長按影片加入）、最近播放、連續播放

## 建置
用 Android Studio 開啟，或：

```
gradle assembleDebug
```

每次推到 main，GitHub Actions 會自動建置 debug APK，可在 Actions 頁面下載。

## M4
- 私密資料夾：PIN（PBKDF2 雜湊）＋指紋解鎖，錯誤 5 次鎖 30 秒，離開分頁或 app 自動上鎖
- 影片以 AES-256-CTR 加密存到 app 內部儲存（金鑰由 Android Keystore 保護），播放時即時解密、可拖動進度；縮圖與清單索引也加密
- 可還原到 Movies/NewVideoPlayer，或永久刪除
- 長按影片：加入播放清單、分享、重新命名、詳細資訊、移到私密資料夾、刪除（Android 11+ 會跳系統確認）

## M5
- Chromecast 投放：同 Wi-Fi 搜尋電視，手機內建小型 HTTP 伺服器（支援 Range 拖動），私密影片也能投放；投放時手機變成遙控器，停止後回到原進度
- 線上字幕：OpenSubtitles 搜尋下載（中英/繁中/簡中/英文），可選擇登入 OpenSubtitles 帳號提高每日額度
  - 需要 app 自己的 consumer key：在 opensubtitles.com 申請後，建置時設定 `OPENSUBTITLES_API_KEY`（Gradle property 或環境變數）
- AI 字幕（離線、免費）：whisper.cpp v1.9.5 在手機上辨識，解碼成 16 kHz 單聲道、每 5 分鐘一段處理，輸出 SRT 並自動載入。模型首次下載（tiny 30 MB / base 56 MB / small 181 MB，q5_1，SHA-256 驗證），中文會提示輸出繁體。原生程式在 app/src/main/cpp，建置時由 CMake 下載 whisper.cpp 原始碼
- 轉成音訊檔：不重新編碼直接抽出音軌（AAC→M4A、Opus/Vorbis→WebM），存到 Music/NewVideoPlayer。真正的 MP3 需等加入 FFmpeg/LAME
