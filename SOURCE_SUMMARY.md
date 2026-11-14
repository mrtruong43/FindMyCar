# FindMyCar — Tổng hợp Source Code & Kiến trúc (Cập nhật M2)

> Tài liệu này được cập nhật sau mỗi lần build debug để đội ngũ dev nắm nhanh cấu trúc, luồng dữ liệu và trạng thái hiện tại của ứng dụng.

## 1. Thông tin chung & Toolchain

- **Package:** `com.truong.findmycar`
- **Module:** `:app` (single-module)
- **SDK:** `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- **Build & Công nghệ:**
  - Gradle `9.3.1`, AGP `9.1.1` (Kotlin tích hợp sẵn), Kotlin `2.3.20`, KSP `2.3.6` (không dùng `kapt`).
  - Jetpack Compose (Material 3, Dark Mode mặc định, tắt Dynamic Color, màu nhấn `#2EE6A6`).
  - Room `2.7.2`, DataStore Preferences `1.1.7`, Navigation Compose `2.9.0`, Google Play Services Location `21.3.0`.
  - **Lưu ý build CLI:** Cần trỏ `JAVA_HOME` tới JDK 17 (`C:\Users\Thu\AppData\Local\Programs\openjdk-17\jdk-17.0.19+10`).
- **File APK hiện tại:** `findmycar-debug.apk` (`12,603,086` bytes / ~12.0 MB).

---

## 2. Cấu trúc thư mục & Vai trò từng file

```text
app/src/main/java/com/truong/findmycar/
├── FindMyCarApp.kt                 # Application + AppContainer (DB, Repo, SettingsStore, ParkingDetector)
├── MainActivity.kt                 # Single-Activity (launchMode="singleTop"), enableEdgeToEdge dark
├── data/
│   ├── db/
│   │   ├── Entities.kt             # ParkingSpotEntity, TrackPointEntity (FK CASCADE), SpotSource, SpotStatus
│   │   ├── ParkingSpotDao.kt       # observeActiveSpot(), insertAsSingleActive(), markAnchorSet()
│   │   ├── TrackPointDao.kt        # Truy vấn/lưu các điểm dấu chân theo spotId
│   │   └── AppDatabase.kt          # RoomDatabase (version 1)
│   ├── ParkingRepository.kt        # Đảm bảo bất biến tối đa 1 spot ACTIVE; cập nhật note/photo/anchor
│   └── SettingsStore.kt            # DataStore Preferences (userHeightM, floorHeightM, autoDetect, haptic, onboardingCompleted)
├── detect/
│   ├── ParkingDetector.kt          # Luồng lưu thủ công: GPS (timeout 5s) + áp suất (2s) song song
│   └── BootReceiver.kt             # Stub RECEIVE_BOOT_COMPLETED sẵn sàng cho M3 đăng ký lại Activity Transition
├── tracking/
│   ├── TrackingStateHolder.kt      # Singleton StateFlow (isTracking, elapsedSeconds, activeSpotId)
│   └── TrackingService.kt          # Foreground Service (location): notification, PARTIAL_WAKE_LOCK 35p, auto-stop 30p & pin <10%
├── ui/
│   ├── theme/                      # Color.kt, Shape.kt, Type.kt, Theme.kt
│   ├── components/
│   │   ├── FloorBadge.kt           # Pill badge tầng/áp suất
│   │   ├── TimeFormat.kt           # Định dạng thời gian trôi qua tiếng Việt
│   │   └── BatteryOptimizationCard.kt # Thẻ kiểm tra & mở cài đặt tắt tối ưu pin Samsung One UI
│   ├── onboarding/
│   │   └── OnboardingScreen.kt     # 3 bước lần đầu mở app: giá trị -> xin quyền tuần tự (2 bước vị trí) -> tối ưu pin Samsung
│   ├── settings/
│   │   ├── SettingsViewModel.kt    # Đọc/ghi AppSettings từ SettingsStore
│   │   └── SettingsScreen.kt       # Cài đặt chung, đo đạc, thẻ pin Samsung, nút mở lại Onboarding & App Settings
│   ├── home/
│   │   ├── HomeViewModel.kt        # Kết hợp activeSpot + isSaving + TrackingStateHolder
│   │   └── HomeScreen.kt           # Home: Empty/Active + thẻ "Đang ghi đường đi" & nút "Tôi đã tới lối ra/thang máy"
│   ├── detail/
│   │   ├── SpotDetailViewModel.kt  # Ghi chú, giọng nói, ảnh
│   │   └── SpotDetailScreen.kt     # Sửa ghi chú, RecognizerIntent vi-VN, TakePicture + FileProvider, geo: map intent
│   └── navigation/
│       └── NavGraph.kt             # Kiểm tra onboardingCompleted khi khởi động; routes onboarding, home, detail, settings, stubs
└── util/
    ├── GeoMath.kt                  # Thuật toán thuần Kotlin (độ cao khí áp, tầng, bước chân, geo URI)
    ├── Permissions.kt              # Kiểm tra & mở cài đặt quyền (FG/BG location 2 bước, Activity, Notifications, Battery)
    └── PhotoUtils.kt               # FileProvider URI & decode ảnh có scale
```

---

## 3. Luồng hoạt động chính (M1 + M2)

1. **Khởi động & Onboarding (`NavGraph` -> `OnboardingScreen`):**
   - Đọc `onboardingCompleted` từ `SettingsStore`. Nếu `false`, mở 3 màn Onboarding:
     - Bước 1: Giới thiệu tính năng lưu & tìm xe trong hầm không cần GPS/mạng.
     - Bước 2: Xin quyền tuần tự (Vị trí khi dùng -> Vị trí luôn cho phép [chỉ mở sau khi có vị trí thường] -> Nhận diện di chuyển -> Thông báo). Có nút `"Bỏ qua"` để dùng chế độ lưu thủ công.
     - Bước 3: Hướng dẫn tắt tối ưu pin Samsung One UI (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
2. **Lưu xe & `TrackingService` (`HomeScreen` -> `ParkingDetector` -> `TrackingService`):**
   - Bấm **"Lưu xe"** → lưu `ParkingSpotEntity` (`ACTIVE`) với GPS + áp suất.
   - Nếu có quyền vị trí foreground, tự khởi động `TrackingService` (`foregroundServiceType="location"`):
     - Hiện notification cố định **"Đang ghi đường đi từ xe"** kèm thời gian `mm:ss` và nút **"Dừng ghi"**.
     - Giữ `PARTIAL_WAKE_LOCK` (timeout an toàn 35 phút, luôn giải phóng trong `onDestroy()`).
     - Tự dừng nếu chạy quá **30 phút** hoặc pin xuống dưới **10%** (`ACTION_BATTERY_CHANGED`).
   - Trên Home hiện thẻ **"Đang ghi đường đi"** và nút **"Tôi đã tới lối ra/thang máy"** (đánh dấu `anchorSet = true` và dừng service).

---

## 4. Trạng thái các mốc (Roadmap)

- **M1 (Hoàn thành):** Nền tảng, Room + KSP, DataStore, Home, Chi tiết spot, Lưu thủ công.
- **M2 (Hoàn thành):** Onboarding 3 bước, xin quyền 2 bước, hướng dẫn tối ưu pin Samsung, `SettingsScreen`, `TrackingService` foreground + WakeLock + auto-stop 30p/pin <10%.
- **M3 (Chưa làm):** Phát hiện đỗ xe tự động (Activity Transition + `RecentLocationBuffer`).
- **M4 (Chưa làm):** Ghi dấu chân dead reckoning (`StepTracker`, `HeadingEstimator`, `PressureAltimeter`, `PathModel`, Debug CSV).
- **M5 (Chưa làm):** Dẫn đường quay về 2 pha (`GuidanceEngine`, `ArrowCompass`, rung gần xe).
- **M6 (Chưa làm):** Lịch sử, hiệu chỉnh bước, hoàn thiện.
