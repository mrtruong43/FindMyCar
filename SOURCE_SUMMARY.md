# FindMyCar — Tổng hợp Source Code & Kiến trúc (Cập nhật M4)

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
- **File APK hiện tại:** `findmycar-debug.apk` (`12,680,002` bytes / ~12.09 MB).

---

## 2. Cấu trúc thư mục & Vai trò từng file

```text
app/src/main/java/com/truong/findmycar/
├── FindMyCarApp.kt                 # Application + AppContainer; tự đăng ký ActivityTransition khi khởi động
├── MainActivity.kt                 # Single-Activity (singleTop), xử lý deep-link EXTRA_OPEN_SPOT_DETAIL_ID
├── data/
│   ├── db/                         # Entities, ParkingSpotDao (finalizeAnchor), TrackPointDao, AppDatabase
│   ├── ParkingRepository.kt        # Bất biến tối đa 1 spot ACTIVE; insertTrackPoints(), finalizeAnchor()
│   └── SettingsStore.kt            # DataStore Preferences
├── detect/
│   ├── ActivityTransitionManager.kt  # Đăng ký/hủy IN_VEHICLE & WALKING với PendingIntent FLAG_MUTABLE
│   ├── ActivityTransitionReceiver.kt # BroadcastReceiver dùng goAsync() -> ParkingDetector
│   ├── BootReceiver.kt               # RECEIVE_BOOT_COMPLETED -> đăng ký lại ActivityTransition
│   ├── RecentLocationBuffer.kt       # Ring buffer 5 phút, lọc fix <= 50m
│   ├── ParkingStateMachine.kt        # Thuần Kotlin: luật 2 phút & chống trùng 90s
│   └── ParkingDetector.kt            # Lưu thủ công & tự động, LocationUpdates khi lái xe, barometer 2s
├── guidance/
│   └── CircularFilter.kt           # Thuần Kotlin: lọc thông thấp góc trên sin/cos tránh nhảy 0°/360°
├── tracking/
│   ├── HeadingEstimator.kt         # Thuần Kotlin: Mode A (cầm tay) + Mode B (PCA 2x2 trong túi + heel-strike)
│   ├── PressureAltimeter.kt        # Thuần Kotlin: trung bình trượt 1.5s + lọc thông thấp -> độ cao & tầng
│   ├── PathModel.kt                # Thuần Kotlin: cập nhật bước đi, đảo đường, chiếu polyline, lookahead
│   ├── StepTracker.kt              # SensorAdapter: TYPE_STEP_DETECTOR (kèm hardware batching 1s)
│   ├── HeadingSensorAdapter.kt     # SensorAdapter: TYPE_ROTATION_VECTOR + TYPE_LINEAR_ACCELERATION + giảm tần số khi đứng yên 10s
│   ├── PressureSensorAdapter.kt    # SensorAdapter: TYPE_PRESSURE -> PressureAltimeter
│   ├── TrackingStateHolder.kt      # StateFlow: isTracking, elapsed, telemetry realtime, bộ đệm log CSV
│   └── TrackingService.kt          # Foreground Service: ghi dấu chân theo lô (10 điểm / 5s), giám sát GPS <20m 20s tự chốt neo
├── ui/
│   ├── theme/                      # Color.kt, Shape.kt, Type.kt, Theme.kt
│   ├── components/                 # FloorBadge.kt, TimeFormat.kt, BatteryOptimizationCard.kt
│   ├── onboarding/                 # OnboardingScreen.kt
│   ├── settings/                   # SettingsViewModel.kt, SettingsScreen.kt (bấm 7 lần vào phiên bản mở Debug)
│   ├── debug/                      # DebugScreen.kt (telemetry realtime + ghi/xuất CSV qua FileProvider)
│   ├── home/                       # HomeViewModel.kt, HomeScreen.kt (hiển thị số bước & nút chốt lối ra)
│   ├── detail/                     # SpotDetailViewModel.kt, SpotDetailScreen.kt
│   └── navigation/                 # NavGraph.kt
└── util/
    ├── GeoMath.kt                  # Thuần Kotlin: Haversine, bearing, độ cao khí áp, tầng, geo URI
    ├── Permissions.kt              # Kiểm tra & mở cài đặt quyền
    └── PhotoUtils.kt               # FileProvider URI & decode ảnh có scale
```

---

## 3. Luồng ghi dấu chân & Chốt điểm neo (M4)

1. **Cảm biến & Thuật toán (`TrackingService`):**
   - `StepTracker` (`TYPE_STEP_DETECTOR`) phát hiện từng bước với chiều dài `stepLengthM = 0.415 * userHeightM`.
   - `HeadingSensorAdapter` tự chuyển giữa Mode A (cầm tay dùng `TYPE_ROTATION_VECTOR`) và Mode B (trong túi dùng `TYPE_LINEAR_ACCELERATION` xoay về hệ tọa độ thế giới -> PCA 2x2 trong `HeadingEstimator`). Nếu đứng yên **10 giây** không có bước, tự hạ tần số xuống `SENSOR_DELAY_NORMAL` để tiết kiệm pin.
   - `PressureSensorAdapter` (`TYPE_PRESSURE`) tính độ cao tương đối & chênh lệch tầng so với áp suất lúc rời xe.
2. **Ghi DB theo lô & Điểm neo:**
   - `TrackPointEntity` được đệm trong RAM và ghi xuống Room mỗi **10 điểm hoặc 5 giây** (và flush toàn bộ khi dừng).
   - Tự động chốt điểm neo (`anchorSet = true`, `anchorLat/Lon`, `anchorPressureHpa`, `anchorRelHeightM`) và dừng service khi GPS ngoài trời đạt `< 20 m` liên tục **20 giây**, hoặc khi người dùng bấm **"Tôi đã tới lối ra/thang máy"**, hoặc khi chạm ngưỡng an toàn (30 phút / pin `< 10%`).
3. **Màn hình Debug ẩn (`DebugScreen`):**
   - Bấm **7 lần** vào dòng phiên bản ở cuối `SettingsScreen` để mở. Cho phép xem telemetry realtime, bật/tắt ghi log CSV và chia sẻ file `.csv` qua `FileProvider` (`ACTION_SEND`).

---

## 4. Trạng thái các mốc (Roadmap)

- **M1 (Hoàn thành):** Nền tảng, Room + KSP, DataStore, Home, Chi tiết spot, Lưu thủ công.
- **M2 (Hoàn thành):** Onboarding 3 bước, xin quyền 2 bước, hướng dẫn tối ưu pin Samsung, `SettingsScreen`, `TrackingService`.
- **M3 (Hoàn thành):** Phát hiện đỗ xe tự động (`ActivityTransitionReceiver`, `BootReceiver`, `RecentLocationBuffer`, `ParkingStateMachine`).
- **M4 (Hoàn thành):** Bộ engine toán thuần Kotlin + unit test (M4A), tích hợp cảm biến thực, ghi DB theo lô, chốt điểm neo & Debug CSV (M4B).
- **M5 (Chưa làm):** Dẫn đường quay về 2 pha (`GuidanceEngine`, `ArrowCompass`, rung gần xe).
- **M6 (Chưa làm):** Lịch sử, hiệu chỉnh bước, hoàn thiện.
