# FindMyCar — Tổng hợp Source Code & Kiến trúc (Hoàn thiện M1–M6 & Nâng cấp 3D Hologram / Sensor)

> Tài liệu này tổng hợp toàn bộ kiến trúc, luồng dữ liệu, vai trò từng file và các nâng cấp mới nhất của ứng dụng **FindMyCar**.

## 1. Thông tin chung & Toolchain

- **Package:** `com.truong.findmycar`
- **Module:** `:app` (single-module)
- **SDK:** `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- **Build & Công nghệ:**
  - Gradle `9.3.1`, AGP `9.1.1` (Kotlin tích hợp sẵn), Kotlin `2.3.20`, KSP `2.3.6` (không dùng `kapt`).
  - Jetpack Compose (Material 3, Dark Mode mặc định `#0B0F14`, Bento Surface `#131A22`, màu nhấn `#2EE6A6`, cảnh báo/hầm `#FFB020`).
  - Room `2.7.2`, DataStore Preferences `1.1.7`, Navigation Compose `2.9.0`, Google Play Services Location `21.3.0`.
  - **Lưu ý build CLI:** Cần trỏ `JAVA_HOME` tới JDK 17 (`C:\Users\Thu\AppData\Local\Programs\openjdk-17\jdk-17.0.19+10`).
- **File APK hiện tại:** `findmycar-debug.apk` (`13,521,260` bytes / ~12.89 MB).
- **Unit test:** 35/35 test pass trên JVM (không phụ thuộc thiết bị Android).

---

## 2. Cấu trúc thư mục & Vai trò từng file

```text
app/src/main/java/com/truong/findmycar/
├── FindMyCarApp.kt                 # Application + AppContainer; tự đăng ký ActivityTransition khi khởi động
├── MainActivity.kt                 # Single-Activity (singleTop), deep-link EXTRA_OPEN_SPOT_DETAIL_ID
├── data/
│   ├── db/                         # Entities, ParkingSpotDao (observeHistorySpots, deleteAll), TrackPointDao, AppDatabase
│   ├── ParkingRepository.kt        # Spot ACTIVE đơn nhất, lịch sử, cập nhật note/photo, xóa spot/toàn bộ dữ liệu
│   └── SettingsStore.kt            # DataStore Preferences (userHeightM, floorHeightM, calibratedStepLengthM, autoDetect, haptic, onboarding, barometerNotice, vehicleType: SCOOTER/CAR)
├── detect/
│   ├── ActivityTransitionManager.kt  # Đăng ký/hủy IN_VEHICLE & WALKING (PendingIntent FLAG_MUTABLE)
│   ├── ActivityTransitionReceiver.kt # BroadcastReceiver dùng goAsync() -> ParkingDetector
│   ├── BootReceiver.kt               # RECEIVE_BOOT_COMPLETED -> đăng ký lại ActivityTransition
│   ├── RecentLocationBuffer.kt       # Ring buffer 5 phút, lọc fix <= 50m
│   ├── ParkingStateMachine.kt        # Thuần Kotlin: luật 2 phút, chống trùng 90s chỉ cho auto-detect, resetCooldown() khi FOUND/xóa
│   ├── ParkingEnvironmentMode.kt     # Thuần Kotlin: 3 chế độ AUTO / UNDERGROUND / OUTDOOR & resolver
│   └── ParkingDetector.kt            # Lưu thủ công (theo 3 chế độ) & tự động, LocationUpdates khi lái xe, barometer 2s, notification
├── guidance/
│   ├── CircularFilter.kt           # Thuần Kotlin: lọc thông thấp góc sin/cos + updateAdaptive() (lọc nhiễu bước đi <12°, tăng alpha 0.45f khi rẽ >35°)
│   └── GuidanceEngine.kt           # Thuần Kotlin: điều hướng 2 pha, khóa targetBearingDeg vùng gần (<=4m Phase 2 / <=7m Phase 1), chống jitter <3m, lọc tầng
├── tracking/
│   ├── HeadingEstimator.kt         # Thuần Kotlin: Mode A (updateAdaptive) + Mode B (PCA 2x2, cửa sổ 2.5s, minEigenRatio 2.5f)
│   ├── PressureAltimeter.kt        # Thuần Kotlin: trung bình trượt 1.5s + lọc thông thấp
│   ├── PathModel.kt                # Thuần Kotlin: bước đi, đảo đường, chiếu polyline, lookahead
│   ├── StepTracker.kt              # SensorAdapter: TYPE_STEP_DETECTOR (batching 1s)
│   ├── HeadingSensorAdapter.kt     # SensorAdapter: chiếu vector đa tư thế (-Z dựng đứng / +Y phẳng-nghiêng cạnh), giữ hướng khi nghe điện thoại (TYPE_PROXIMITY), lọc nhiễu từ thép hầm (TYPE_MAGNETIC_FIELD), vung tay >= -0.50f
│   ├── PressureSensorAdapter.kt    # SensorAdapter: TYPE_PRESSURE
│   ├── TrackingStateHolder.kt      # StateFlow: telemetry realtime + bộ đệm log CSV
│   └── TrackingService.kt          # Foreground Service: ghi dấu chân theo lô, nút thông báo "Chốt lối ra / thang máy", bộ lọc neo hầm 3 lớp, ghi tiếp dấu chân
├── ui/
│   ├── theme/                      # Color.kt (#0B0F14, BentoCardSurface #131A22 88%, #2EE6A6, #FFB020), Shape.kt, Type.kt, Theme.kt
│   ├── components/
│   │   ├── ScooterHologramStage.kt # Lưới 3D isometric thể tích (Xe tay ga / Ô tô) + radar phối cảnh (B/Đ/N/T), 3 chế độ READY_TO_PARK / PARKED_LOCKED / GUIDING_TO_VEHICLE, xoay chạm 360° chỉ ở READY_TO_PARK, gyroscope theo Lifecycle.RESUMED
│   │   ├── TripCompletedOverlay.kt # Lớp phủ chúc mừng đã tìm thấy xe (checkmark spring, vòng radar, hạt sáng, rung, tự đóng sau 2.5s)
│   │   ├── ArrowCompass.kt         # La bàn vector cũ (giữ lại tham chiếu)
│   │   ├── FloorBadge.kt           # Nhãn chênh lệch tầng
│   │   ├── TimeFormat.kt           # Định dạng thời gian trôi qua & formatParkingDuration()
│   │   ├── BatteryOptimizationCard.kt
│   │   └── EditAnchorBottomSheet.kt
│   ├── onboarding/                 # OnboardingScreen.kt (3 bước, xin quyền 2 bước, tối ưu pin Samsung)
│   ├── home/                       # HomeViewModel.kt, HomeScreen.kt (Hero 3D Hologram Bento Card ở Đỗ xe; State A ghi lối ra vs State B đã đỗ; chọn tầng/ghi chú/camera sau khi lưu trong CarIdentityBentoCard; TripCompletedOverlay khi "Đã lấy xe")
│   ├── detail/                     # SpotDetailViewModel.kt, SpotDetailScreen.kt (ghi chú, giọng nói vi-VN, ảnh, geo:)
│   ├── find/                       # FindCarViewModel.kt, FindCarScreen.kt (La bàn 3D Hologram 60fps với CircularFilter(0.30f) + bù nghiêng upright, nút "Ghi chú và ảnh" 1 dòng, TripCompletedOverlay khi "Đã tìm thấy xe")
│   ├── history/                    # HistoryViewModel.kt, HistoryScreen.kt (danh sách FOUND/ARCHIVED, xem chi tiết, chia sẻ, xóa kèm file ảnh)
│   ├── settings/                   # SettingsViewModel.kt, SettingsScreen.kt (chọn "Xe tay ga" | "Ô tô", hiệu chỉnh bước, xóa dữ liệu, 7 chạm mở Debug)
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
- **M5 (Hoàn thành):** Dẫn đường quay về 2 pha (`GuidanceEngine`, `FindCarScreen`, rung theo khoảng cách).
- **M6 (Hoàn thành):** `HistoryScreen` (chia sẻ & xóa kèm file ảnh), `SettingsScreen` đầy đủ, `StepCalibrationDialog`, xóa toàn bộ dữ liệu, banner thiếu quyền/khí áp kế, TalkBack accessibility & audit giải phóng tài nguyên/pin.

---

## 4. Nâng cấp UI/UX & Cảm biến sau M6

- **3D Isometric Volumetric Hologram (`ScooterHologramStage.kt`):**
  - Chiếu 3D thực (`project3D(x, y, z)` + `depth3D`) với thân xe đùn khối `±z`, đổ bóng gradient bán trong suốt, bánh xe dày, đèn pha kèm nón ánh sáng cho cả **Xe tay ga** (mặc định) và **Ô tô** (chọn trong Cài đặt).
  - Màn hình **Đỗ xe (`READY_TO_PARK`)**: thẻ Hero Hologram chiếm trọn vùng giữa `weight(1f)`, cho phép chạm-vuốt xoay 360° kèm quán tính.
  - Màn hình **Đã lưu / Đang ghi (`PARKED_LOCKED`)**: khóa thao tác kéo xoay để tránh xung đột với chip chọn tầng (`B3`, `B2`, `B1`, `Trệt`, `Tầng 2`), ô ghi chú cột và nút Camera tích hợp ngay trong `CarIdentityBentoCard`.
  - Màn hình **Tìm xe (`GUIDING_TO_VEHICLE`)**: thay mũi tên phẳng bằng mô hình 3D xoay 60fps theo góc tương đối + vành la bàn `B / Đ / N / T` xoay theo hướng máy, zoom `1.15x` + sóng xung kích hướng tâm khi `< 10m`.
- **Xác nhận hoàn tất chuyến đi (`TripCompletedOverlay.kt`):** Hiển thị khi nhấn `"Đã tìm thấy xe"` hoặc `"Đã lấy xe"`, gồm dấu tích nảy spring, vòng radar, tóm tắt thời gian gửi & ghi chú, tự đóng sau 2,5 giây.
- **Ổn định la bàn & cảm biến đa tư thế (`CircularFilter.kt`, `HeadingEstimator.kt`, `HeadingSensorAdapter.kt`, `GuidanceEngine.kt`, `FindCarViewModel.kt`):**
  - Chiếu trực tiếp vector hướng từ `rotationMatrix` (`-Z` khi cầm dựng đứng `|topUp| > 0.707f`, `+Y` khi đặt phẳng hoặc buông tay cạnh bên), giữ nguyên hướng khi nghe điện thoại (`TYPE_PROXIMITY`), siết lọc khi nhiễu từ thép hầm (`TYPE_MAGNETIC_FIELD` ngoài `20..85 µT`), và cho phép vung tay tự nhiên (`topUp >= -0.50f`).
  - Bộ lọc thích ứng `CircularFilter.updateAdaptive()` (chống rung khi đi thẳng, phản hồi nhanh `0.45f` khi rẽ góc `> 35°`), siết `HeadingEstimator` PCA (`2.5s`, `minEigenRatio = 2.5f`), loại bỏ lọc kép ở `FindCarViewModel`, và khóa `targetBearingDeg` trong vùng gần (`<= 4.0m` Phase 2, `<= 7.0m` Phase 1 ngoài trời).
