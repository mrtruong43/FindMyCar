# FindMyCar — Tổng hợp Source Code & Kiến trúc (Hoàn thiện M1–M6)

> Tài liệu này tổng hợp toàn bộ kiến trúc, luồng dữ liệu và vai trò từng file của ứng dụng sau khi hoàn thành đầy đủ cả 6 mốc (M1–M6).

## 1. Thông tin chung & Toolchain

- **Package:** `com.truong.findmycar`
- **Module:** `:app` (single-module)
- **SDK:** `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- **Build & Công nghệ:**
  - Gradle `9.3.1`, AGP `9.1.1` (Kotlin tích hợp sẵn), Kotlin `2.3.20`, KSP `2.3.6` (không dùng `kapt`).
  - Jetpack Compose (Material 3, Dark Mode mặc định, tắt Dynamic Color, màu nhấn `#2EE6A6`).
  - Room `2.7.2`, DataStore Preferences `1.1.7`, Navigation Compose `2.9.0`, Google Play Services Location `21.3.0`.
  - **Lưu ý build CLI:** Cần trỏ `JAVA_HOME` tới JDK 17 (`C:\Users\Thu\AppData\Local\Programs\openjdk-17\jdk-17.0.19+10`).
- **File APK hiện tại:** `findmycar-debug.apk` (`13,517,796` bytes / ~12.89 MB).
- **Unit test:** 31/31 test pass trên JVM (không phụ thuộc thiết bị Android).

---

## 2. Cấu trúc thư mục & Vai trò từng file

```text
app/src/main/java/com/truong/findmycar/
├── FindMyCarApp.kt                 # Application + AppContainer; tự đăng ký ActivityTransition khi khởi động
├── MainActivity.kt                 # Single-Activity (singleTop), deep-link EXTRA_OPEN_SPOT_DETAIL_ID
├── data/
│   ├── db/                         # Entities, ParkingSpotDao (observeHistorySpots, deleteAll), TrackPointDao, AppDatabase
│   ├── ParkingRepository.kt        # Spot ACTIVE đơn nhất, lịch sử, xóa spot/toàn bộ dữ liệu
│   └── SettingsStore.kt            # DataStore Preferences (userHeightM, floorHeightM, calibratedStepLengthM, autoDetect, haptic, onboarding, barometerNotice)
├── detect/
│   ├── ActivityTransitionManager.kt  # Đăng ký/hủy IN_VEHICLE & WALKING (PendingIntent FLAG_MUTABLE)
│   ├── ActivityTransitionReceiver.kt # BroadcastReceiver dùng goAsync() -> ParkingDetector
│   ├── BootReceiver.kt               # RECEIVE_BOOT_COMPLETED -> đăng ký lại ActivityTransition
│   ├── RecentLocationBuffer.kt       # Ring buffer 5 phút, lọc fix <= 50m
│   ├── ParkingStateMachine.kt        # Thuần Kotlin: luật 2 phút, chống trùng 90s chỉ cho auto-detect, resetCooldown() khi FOUND/xóa
│   ├── ParkingEnvironmentMode.kt     # Thuần Kotlin: 3 chế độ AUTO / UNDERGROUND / OUTDOOR & resolver
│   └── ParkingDetector.kt            # Lưu thủ công (theo 3 chế độ) & tự động, LocationUpdates khi lái xe, barometer 2s, notification
├── guidance/
│   ├── CircularFilter.kt           # Thuần Kotlin: lọc thông thấp góc trên sin/cos
│   └── GuidanceEngine.kt           # Thuần Kotlin: điều hướng 2 pha, giữ bearing khi đứng yên <3m, lọc 1 tầng mượt mà, chế độ ngoài trời
├── tracking/
│   ├── HeadingEstimator.kt         # Thuần Kotlin: Mode A + Mode B (PCA 2x2)
│   ├── PressureAltimeter.kt        # Thuần Kotlin: trung bình trượt 1.5s + lọc thông thấp
│   ├── PathModel.kt                # Thuần Kotlin: bước đi, đảo đường, chiếu polyline, lookahead
│   ├── StepTracker.kt              # SensorAdapter: TYPE_STEP_DETECTOR (batching 1s)
│   ├── HeadingSensorAdapter.kt     # SensorAdapter: TYPE_ROTATION_VECTOR + TYPE_LINEAR_ACCELERATION + giảm tần số khi đứng yên 10s
│   ├── PressureSensorAdapter.kt    # SensorAdapter: TYPE_PRESSURE
│   ├── TrackingStateHolder.kt      # StateFlow: telemetry realtime + bộ đệm log CSV
│   └── TrackingService.kt          # Foreground Service: ghi dấu chân theo lô, nút thông báo "Chốt lối ra / thang máy", bộ lọc neo hầm 3 lớp, ghi tiếp dấu chân
├── ui/
│   ├── theme/                      # Color.kt (#0B0F14, BentoCardSurface #131A22 88%, #2EE6A6, #FFB020), Shape.kt, Type.kt, Theme.kt
│   ├── components/                 # ArrowCompass.kt (ticks N/E/S/W + radar rings), FloorBadge.kt, TimeFormat.kt, BatteryOptimizationCard.kt, EditAnchorBottomSheet.kt
│   ├── onboarding/                 # OnboardingScreen.kt (3 bước, xin quyền 2 bước, tối ưu pin Samsung)
│   ├── home/                       # HomeViewModel.kt, HomeScreen.kt (Bento Auto-Layout 22:9, GPS/Sensor Bento cards, Pre-Save Quick Context Card, Reactive 3-mode selector, Compact Guard <450dp)
│   ├── detail/                     # SpotDetailViewModel.kt, SpotDetailScreen.kt (ghi chú, giọng nói vi-VN, ảnh, geo:)
│   ├── find/                       # FindCarViewModel.kt, FindCarScreen.kt (Compass weight(1f) + Unified frosted-glass Bottom Dock 28.dp)
│   ├── history/                    # HistoryViewModel.kt, HistoryScreen.kt (danh sách FOUND/ARCHIVED, xem chi tiết, chia sẻ, xóa kèm file ảnh)
│   ├── settings/                   # SettingsViewModel.kt, SettingsScreen.kt, StepCalibrationDialog.kt (hiệu chỉnh bước, xóa dữ liệu, 7 chạm mở Debug)
│   ├── debug/                      # DebugScreen.kt (telemetry realtime + xuất CSV)
│   └── navigation/                 # NavGraph.kt
└── util/
    ├── GeoMath.kt                  # Thuần Kotlin: Haversine, bearing, độ cao khí áp, tầng, geo URI
    ├── Permissions.kt              # Kiểm tra & mở cài đặt quyền
    └── PhotoUtils.kt               # FileProvider URI, decode ảnh có scale, deletePhotoFile(), deleteAllPhotos()
```

---

## 3. Trạng thái các mốc (Roadmap)

- **M1 (Hoàn thành):** Nền tảng, Room + KSP, DataStore, Home, Chi tiết spot, Lưu thủ công.
- **M2 (Hoàn thành):** Onboarding 3 bước, xin quyền 2 bước, hướng dẫn tối ưu pin Samsung, `TrackingService`.
- **M3 (Hoàn thành):** Phát hiện đỗ xe tự động (`ActivityTransitionReceiver`, `BootReceiver`, `RecentLocationBuffer`).
- **M4 (Hoàn thành):** Dead reckoning (`HeadingEstimator`, `PressureAltimeter`, `PathModel`, cảm biến thực, chốt neo, Debug CSV).
- **M5 (Hoàn thành):** Dẫn đường quay về 2 pha (`GuidanceEngine`, `ArrowCompass`, `FindCarScreen`, rung theo khoảng cách).
- **M6 (Hoàn thành):** `HistoryScreen` (chia sẻ & xóa kèm file ảnh), `SettingsScreen` đầy đủ, `StepCalibrationDialog`, xóa toàn bộ dữ liệu, banner thiếu quyền/khí áp kế, TalkBack accessibility & audit giải phóng tài nguyên/pin.
