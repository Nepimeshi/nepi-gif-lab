# Changelog

All notable changes to **Nepi GIF Lab** are documented in this file.

---

## v1.2.6 (`versionCode 3`)

### Changed
- Migrated Android `applicationId` and Gradle `namespace` from `com.aistudio.gifstudio.vqxkrm` / `com.example` to `com.nepimeshi.nepigiflab`.
- Updated `AndroidManifest.xml` component class names and generated `R` / `BuildConfig` imports for clean resolution under `com.nepimeshi.nepigiflab` while preserving `${applicationId}.fileprovider`.
- Updated `README.md` with the new Application ID, version metadata, and verified cryptocurrency donation addresses and transfer guidance.

> **Upgrade note:** Because `applicationId` changed from `com.aistudio.gifstudio.vqxkrm` to `com.nepimeshi.nepigiflab`, Android treats `v1.2.6` as a distinct app installation from earlier pre-release test builds.

---

## v1.2.5 (`versionCode 2`)

### Changed
- Restored the `release` Gradle signing configuration and added AGP 9.1.1-compatible automatic versioned release APK naming (`Nepi-GIF-Lab-v{versionName}.apk`).

---

## Pre-release Development History (`1.0`, `versionCode 1`)

#### Core Offline Video-to-GIF Studio
- Built the native Android UI in Kotlin and Jetpack Compose (Material 3) with a charcoal dark theme, lavender accents, and custom Nepi mascot branding.
- Implemented `VideoFrameSource` for local video frame extraction and `GifConversionEngine` + `Gif89aStreamEncoder` for streaming, pure-Kotlin GIF89a export directly on-device with zero network permissions.
- Added interactive timeline trimming, frame-by-frame stepping, numerical timestamp input, draggable crop box with aspect ratio presets (`Original`, `1:1`, `4:3`, `16:9`, `9:16`), 90° clockwise rotation, unrestricted custom resolution (clamped to source dimensions), speed control (`0.25×`–`4.0×`), reverse playback, and ping-pong looping.
- Added MediaStore export to `Pictures/Nepi GIF Lab`, Room SQLite conversion history (`GifStudioDatabase`), external viewer opening, system share sheet integration, and WorkManager background processing (`GifConversionWorker`).

#### Animated GIF-to-GIF Import & Alpha Transparency
- Added `AnimatedGifFrameSource` for memory-bounded indexed decoding of animated GIF87a and GIF89a files, preserving variable per-frame delays, disposal methods, and NETSCAPE2.0 loop counts.
- Added full alpha transparency preservation across GIF-to-GIF re-encoding, excluding transparent pixels from color clustering and error diffusion, plus an adaptive transparency checkerboard in previews and PNG history thumbnails for transparent outputs.

#### Color Quantization, Palette & Frame Optimization
- Added `FrameQuantizer` and `GifFrameOptimizer` with Median Cut color quantization (`Auto` and custom `2`–`256` colors) and three dithering modes (`None`, `Ordered Bayer 4×4`, and `Floyd–Steinberg`).
- Added real-time LZW-sampled output size estimation (`GifSizeEstimator`).

#### Saved Presets & Target File Size Optimizer
- Added `ConversionPresetStorage` and preset management UI (`PresetSelectorComponents`) allowing users to save, apply, rename, and delete reusable conversion presets across single-file and batch workflows.
- Added `TargetFileSizeOptimizer` and `TargetFileSizeSheet` to analyze source media against target size limits (such as chat/platform upload caps) and recommend resolution, FPS, palette, or WebP quality adjustments.

#### Batch Conversion Queue & Notifications
- Added mixed Video + Animated GIF batch conversion (`BatchConversionScreen`, `BatchConversionWorker`, `BatchQueueStorage`, `BatchSizeEstimator`) with queue persistence, per-item custom settings overrides, item reordering, single-item retry, cooperative cancellation, and completed-item cleanup.
- Added rich WorkManager progress and completion notifications with quick actions (`ConversionNotificationHelper`, `ConversionActionReceiver`).

#### Share-to-Nepi Intents, Themes & Haptics
- Added `ShareIntentParser` and Android `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filters so videos and GIFs can be shared directly from gallery or messaging apps into the editor or batch queue.
- Added Dark, Light, and System theme modes (`SettingsBottomSheet`, `UserPreferencesRepository`) and subtle semantic haptic feedback (`StudioHaptics`) with a user preference toggle.

#### Animated WebP Export & Cross-App Compatibility
- Added Animated WebP export support (`WebPConversionEngine`) alongside GIF89a, with quality presets (`Lossless`, `High`, `Balanced`, `Smaller`, and `Custom`), transparency support, and format-aware gallery/history handling.
- Updated Animated WebP frame encoding to use a compatibility-first full-canvas compositing pipeline so exported animations preserve crisp quality and reliable playback across Android gallery viewers, messaging platforms, and upload tools.

#### Live Preview Quality & Accuracy Improvements
- Upgraded `LiveGifPreviewRenderer` to use viewport-aware preview resolution rather than low-resolution thumbnails, preserving crispness, aspect ratio, alpha transparency, frame timing, and accurate quantized/dithered output representation while keeping memory usage bounded.

#### Optional Support Section & GitHub Preparation
- Added a subtle, non-intrusive **Support Nepi** card on the home screen opening a dedicated `SupportNepiScreen` for optional direct cryptocurrency donations.
- Implemented centralized public address configuration (`DonationConfig`) and a zero-dependency, 100% offline ISO/IEC 18004 QR code generator (`OfflineQrCodeGenerator`) with one-tap clipboard copy and explicit per-network transfer warnings.
- Prepared repository documentation (`README.md`, `CHANGELOG.md`) and hardened `.gitignore` for public GitHub publication.
