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

## 字幕選擇
- 上方「字幕」按鈕列出內建字幕和所有已載入的外掛字幕（手動、線上、AI 可同時保留），可切換或關閉字幕；新載入的字幕會自動選用
- 載入的字幕（含 AI 字幕）會複製到 app 內永久保存，下次開同一部影片自動帶回，並記住上次選的字幕
- 「更多 → 匯出字幕檔」把目前選用的外掛/AI 字幕存到 Download/NewVideoPlayer（檔名同影片，AI 字幕加 .ai）

## 循環、定時關閉、畫質增強
- 更多 → 循環播放：關閉／單部循環／全部循環（會記住）
- 更多 → 定時關閉：15/30/45/60/90 分鐘或播完這部後暫停
- 更多 → 畫質增強：關閉／輕度／強，GPU 調整對比、飽和度並銳利化（media3-effect），會記住

## 影片／音樂／播放清單
- 底部分頁：影片（資料夾、最近播放）、音樂（歌曲、專輯、演出者、資料夾）、播放清單
- 音樂讀取本機 MediaStore 音樂（Android 13+ 需 READ_MEDIA_AUDIO），可全部播放、隨機播放、搜尋
- 播放歌曲時底部出現迷你播放條，點開有封面、進度、上一首／下一首、隨機、循環、定時關閉、停止；背景與鎖屏控制沿用播放服務
- 播放清單可同時放影片和歌曲
- 私密資料夾暫時隱藏（程式保留）

## FFmpeg 與 MP3
- 自己的 FFmpeg 解碼器（不用 NextLib）：`ffmpeg/build.sh` 編出只含解碼器的 FFmpeg 7.1.5（LGPL 2.1+，未開 GPL/nonfree）與 dav1d 1.5.4（BSD），靜態連進 `libnvpffmpeg.so`
- 音訊：`ffmpeg/Ffmpeg*Audio*`（改自 Media3 1.8.0 decoder_ffmpeg，Apache 2.0）— AC3、E-AC3、DTS、TrueHD、MP3、AAC、FLAC、ALAC、Opus、Vorbis、AMR
- 影片：`FfmpegVideoDecoder`／`FfmpegVideoRenderer`（自寫，自有解碼執行緒，片尾會把 FFmpeg 扣住的畫面全部吐出）— H.264、HEVC、VP8、VP9、AV1、MPEG-4、MPEG-2、H.263，YV12 直接畫到 Surface
- 手機解不了才用 FFmpeg；設定選「軟體解碼」時影片優先用 FFmpeg。軟體解碼時不套用畫質增強
- 本機建置：先 `NDK=/path/to/ndk bash ffmpeg/build.sh`（需 meson、ninja、pkg-config），否則 APK 不含 FFmpeg
- 轉成音訊檔可選 MP3（LAME 3.100，LGPL，192 kbps）或原始音軌（M4A／WebM）
- RMVB、WMV 等容器仍不支援（需要 VLC 引擎）

### 雲端 AI 字幕（Groq）
- AI 產生字幕可切換「雲端（Groq）」：使用 Groq 託管的 whisper-large-v3-turbo，使用者自備免費金鑰（https://console.groq.com/keys）。
- 免費方案：每天 28,800 秒（8 小時）、每小時 7,200 秒音訊、每分鐘 20 次。超過時依 retry-after 自動等待。
- 音訊以 16 kHz 單聲道 WAV、每段 10 分鐘（約 19 MB）上傳。聲音會傳到 Groq。

### 字幕存檔與翻譯
- AI／Groq 產生的字幕會存在影片旁：`影片.<語言>.AI.srt`、`影片.<語言>.Groq.srt`（第一次需授權該資料夾，Android 系統資料夾選擇器）。「自動」語言使用辨識出的語言代碼。
- 更多 → 翻譯字幕：Groq（openai/gpt-oss-120b，每批 40 行，用同一把免費金鑰）或 ML Kit 離線翻譯（每種語言約 30 MB 模型；中文輸出經 ICU 轉為繁體，Android 10+）。輸出 `影片.<語言>.Groq翻譯.srt`／`影片.<語言>.MLKit翻譯.srt`。只支援 SRT／VTT 來源。
