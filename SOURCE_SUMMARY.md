# FindMyCar — Tổng hợp Source Code & Kiến trúc (Cập nhật M3)

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
- **File APK hiện tại:** `findmycar-debug.apk` (`12,621,706` bytes / ~12.04 MB).

---

## 2. Cấu trúc thư mục & Vai trò từng file

```text
app/src/main/java/com/truong/findmycar/
├── FindMyCarApp.kt                 # Application + AppContainer; tự đăng ký ActivityTransition khi khởi động nếu bật autoDetect
├── MainActivity.kt                 # Single-Activity (singleTop), xử lý deep-link EXTRA_OPEN_SPOT_DETAIL_ID từ notification
├── data/
│   ├── db/                         # Entities, ParkingSpotDao, TrackPointDao, AppDatabase
│   ├── ParkingRepository.kt        # Đảm bảo bất biến tối đa 1 spot ACTIVE; cập nhật note/photo/anchor
│   └── SettingsStore.kt            # DataStore Preferences (userHeightM, floorHeightM, autoDetect, haptic, onboardingCompleted)
├── detect/
│   ├── ActivityTransitionManager.kt  # Đăng ký/hủy IN_VEHICLE (ENTER/EXIT) & WALKING (ENTER) với PendingIntent FLAG_MUTABLE
│   ├── ActivityTransitionReceiver.kt # BroadcastReceiver dùng goAsync() chuyển sự kiện sang ParkingDetector
│   ├── BootReceiver.kt               # RECEIVE_BOOT_COMPLETED: dùng goAsync() đọc SettingsStore và đăng ký lại ActivityTransition
│   ├── RecentLocationBuffer.kt       # Ring buffer thread-safe lưu GPS 5 phút gần nhất, lọc fix <= 50m
│   ├── ParkingStateMachine.kt        # Thuần Kotlin: luật 2 phút (IN_VEHICLE EXIT -> WALKING ENTER) & chống trùng 90s
│   └── ParkingDetector.kt            # Điều phối lưu thủ công & tự động, LocationUpdates khi lái xe, barometer 2s, notification
├── tracking/
│   ├── TrackingStateHolder.kt      # Singleton StateFlow (isTracking, elapsedSeconds, activeSpotId)
│   └── TrackingService.kt          # Foreground Service (location): notification, PARTIAL_WAKE_LOCK 35p, auto-stop 30p & pin <10%
├── ui/
│   ├── theme/                      # Color.kt, Shape.kt, Type.kt, Theme.kt
│   ├── components/                 # FloorBadge.kt, TimeFormat.kt, BatteryOptimizationCard.kt
│   ├── onboarding/                 # OnboardingScreen.kt (3 bước, xin quyền 2 bước, tối ưu pin Samsung)
│   ├── settings/                   # SettingsViewModel.kt (bật/tắt ActivityTransition), SettingsScreen.kt
│   ├── home/                       # HomeViewModel.kt, HomeScreen.kt (Empty/Active + thẻ Đang ghi đường đi)
│   ├── detail/                     # SpotDetailViewModel.kt, SpotDetailScreen.kt (ghi chú, giọng nói vi-VN, ảnh, geo:)
│   └── navigation/                 # NavGraph.kt (hỗ trợ deep-link thẳng tới SpotDetail từ notification)
└── util/
    ├── GeoMath.kt                  # Thuật toán thuần Kotlin (độ cao khí áp, tầng, bước chân, geo URI)
    ├── Permissions.kt              # Kiểm tra & mở cài đặt quyền
    └── PhotoUtils.kt               # FileProvider URI & decode ảnh có scale
```

---

## 3. Luồng phát hiện đỗ xe tự động (M3)

1. **Đăng ký Activity Transition (`ActivityTransitionManager`):**
   - Đăng ký khi app khởi động (`FindMyCarApp`), khi cấp quyền ở Onboarding, khi bật trong `SettingsScreen`, và sau khi khởi động lại máy (`BootReceiver`). Hủy đăng ký khi tắt `autoDetectEnabled`.
2. **Đệm vị trí khi lái xe (`IN_VEHICLE ENTER` -> `RecentLocationBuffer`):**
   - Bật `FusedLocationProviderClient.requestLocationUpdates` mức `PRIORITY_BALANCED_POWER_ACCURACY` (chu kỳ 7s, tối thiểu 5s) nạp vào `RecentLocationBuffer` (giữ 5 phút gần nhất).
   - Khi nhận `IN_VEHICLE EXIT` (hoặc quá timeout an toàn 60 phút), tắt cập nhật vị trí ngay để tiết kiệm pin.
3. **Kích hoạt & Chống trùng (`ParkingStateMachine` -> `ParkingDetector`):**
   - Ghi nhận mốc thời gian `IN_VEHICLE EXIT`. Nếu `WALKING ENTER` xảy ra trong vòng **2 phút (120s)** thì kích hoạt lưu tự động (`source = "AUTO_ACTIVITY"`). Nếu đứng yên không đi bộ thì không lưu.
   - Bỏ qua mọi yêu cầu lưu (tự động hoặc thủ công) nếu đã có spot được lưu trong **90 giây** gần nhất.
   - Khi lưu tự động: ưu tiên lấy GPS từ `RecentLocationBuffer.getBestRecentLocation(50f)` (fallback `getCurrentLocation` 5s rồi `null`), đo trung bình áp suất 2s, lưu vào Room, khởi động `TrackingService`, và bắn notification `"Đã lưu vị trí xe. Chạm để thêm ghi chú hoặc ảnh."` mở thẳng màn hình Chi tiết spot.

---

## 4. Trạng thái các mốc (Roadmap)

- **M1 (Hoàn thành):** Nền tảng, Room + KSP, DataStore, Home, Chi tiết spot, Lưu thủ công.
- **M2 (Hoàn thành):** Onboarding 3 bước, xin quyền 2 bước, hướng dẫn tối ưu pin Samsung, `SettingsScreen`, `TrackingService`.
- **M3 (Hoàn thành):** Phát hiện đỗ xe tự động (`ActivityTransitionReceiver`, `BootReceiver`, `RecentLocationBuffer`, `ParkingStateMachine`, notification deep-link).
- **M4 (Chưa làm):** Ghi dấu chân dead reckoning (`StepTracker`, `HeadingEstimator`, `PressureAltimeter`, `PathModel`, Debug CSV).
- **M5 (Chưa làm):** Dẫn đường quay về 2 pha (`GuidanceEngine`, `ArrowCompass`, rung gần xe).
- **M6 (Chưa làm):** Lịch sử, hiệu chỉnh bước, hoàn thiện.
