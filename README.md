# NewVideoPlayer

Android 影片播放器，支援多種格式、手勢控制、記住播放位置。開發規劃見 [docs/plan.md](docs/plan.md)。

## M1 功能（目前）
- 自動掃描手機與 SD 卡上的影片，依資料夾分類，可搜尋
- 影片縮圖與長度
- 全螢幕播放（Media3 ExoPlayer）
- 手勢：左側上下滑調亮度、右側上下滑調音量、左右滑動調進度、雙擊左/右快退/快進 10 秒、雙擊中間暫停
- 記住每部影片的播放位置

## 建置
用 Android Studio 開啟，或：

```
gradle assembleDebug
```

每次推到 main，GitHub Actions 會自動建置 debug APK，可在 Actions 頁面下載。
