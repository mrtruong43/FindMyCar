# FindMyCar — Tổng hợp Source Code & Kiến trúc (Cập nhật M5)

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
- **File APK hiện tại:** `findmycar-debug.apk` (`12,735,926` bytes / ~12.15 MB).

---

## 2. Cấu trúc thư mục & Vai trò từng file

```text
app/src/main/java/com/truong/findmycar/
├── FindMyCarApp.kt                 # Application + AppContainer
├── MainActivity.kt                 # Single-Activity (singleTop), deep-link EXTRA_OPEN_SPOT_DETAIL_ID
├── data/
│   ├── db/                         # Entities, ParkingSpotDao, TrackPointDao, AppDatabase
│   ├── ParkingRepository.kt        # Spot ACTIVE đơn nhất, insertTrackPoints(), getTrackPoints(), finalizeAnchor()
│   └── SettingsStore.kt            # DataStore Preferences
├── detect/
│   ├── ActivityTransitionManager.kt  # Đăng ký/hủy IN_VEHICLE & WALKING
│   ├── ActivityTransitionReceiver.kt # BroadcastReceiver dùng goAsync() -> ParkingDetector
│   ├── BootReceiver.kt               # RECEIVE_BOOT_COMPLETED -> đăng ký lại ActivityTransition
│   ├── RecentLocationBuffer.kt       # Ring buffer 5 phút, lọc fix <= 50m
│   ├── ParkingStateMachine.kt        # Thuần Kotlin: luật 2 phút & chống trùng 90s
│   └── ParkingDetector.kt            # Lưu thủ công & tự động, LocationUpdates khi lái xe, barometer 2s
├── guidance/
│   ├── CircularFilter.kt           # Thuần Kotlin: lọc thông thấp góc trên sin/cos
│   └── GuidanceEngine.kt           # Thuần Kotlin: điều hướng 2 pha (GPS tới neo -> đảo dấu chân trong hầm), <8m Rất gần
├── tracking/
│   ├── HeadingEstimator.kt         # Thuần Kotlin: Mode A + Mode B (PCA 2x2)
│   ├── PressureAltimeter.kt        # Thuần Kotlin: trung bình trượt 1.5s + lọc thông thấp
│   ├── PathModel.kt                # Thuần Kotlin: bước đi, đảo đường, chiếu polyline, lookahead
│   ├── StepTracker.kt              # SensorAdapter: TYPE_STEP_DETECTOR (batching 1s)
│   ├── HeadingSensorAdapter.kt     # SensorAdapter: TYPE_ROTATION_VECTOR + TYPE_LINEAR_ACCELERATION
│   ├── PressureSensorAdapter.kt    # SensorAdapter: TYPE_PRESSURE
│   ├── TrackingStateHolder.kt      # StateFlow: telemetry realtime + bộ đệm log CSV
│   └── TrackingService.kt          # Foreground Service: ghi dấu chân theo lô, chốt neo tự động/thủ công
├── ui/
│   ├── theme/                      # Color.kt, Shape.kt, Type.kt, Theme.kt
│   ├── components/                 # ArrowCompass.kt (Canvas + spring + cone hiệu chỉnh + chấm nhịp thở), FloorBadge.kt, TimeFormat.kt, BatteryOptimizationCard.kt
│   ├── onboarding/                 # OnboardingScreen.kt
│   ├── settings/                   # SettingsViewModel.kt, SettingsScreen.kt (7 chạm mở Debug)
│   ├── debug/                      # DebugScreen.kt (telemetry realtime + xuất CSV)
│   ├── home/                       # HomeViewModel.kt, HomeScreen.kt
│   ├── detail/                     # SpotDetailViewModel.kt, SpotDetailScreen.kt
│   ├── find/                       # FindCarViewModel.kt (GPS + cảm biến + rung theo khoảng cách), FindCarScreen.kt (Precision Finding)
│   └── navigation/                 # NavGraph.kt
└── util/
    ├── GeoMath.kt                  # Thuần Kotlin: Haversine, bearing, độ cao khí áp, tầng, geo URI
    ├── Permissions.kt              # Kiểm tra & mở cài đặt quyền
    └── PhotoUtils.kt               # FileProvider URI & decode ảnh có scale
```

---

## 3. Luồng dẫn đường quay về (M5)

1. **`GuidanceEngine` (thuần Kotlin):**
   - **Thiếu cả GPS và dấu chân:** Chuyển sang `NO_SIGNAL_FALLBACK`, chỉ hiển thị ghi chú, ảnh và tầng kèm lý do rõ ràng.
   - **Chỉ có GPS (spot thủ công không có dấu chân):** Pha `PHASE1_GPS_TO_CAR` dẫn thẳng bằng bearing/khoảng cách GPS tới xe.
   - **Pha 1 (`PHASE1_GPS_TO_ANCHOR`):** Dẫn GPS tới điểm neo (`anchorLat/anchorLon`, fallback `lat/lon` xe). Tự chuyển sang Pha 2 khi cách neo **< 15 m** hoặc khi người dùng bấm **"Tôi đang ở lối ra/thang máy"**.
   - **Pha 2 (`PHASE2_FOOTPRINT`):** Tắt GPS để tiết kiệm pin, đặt lại mốc áp suất tại lối ra, đảo ngược polyline `TrackPoint` từ neo về xe. Mỗi bước chân chiếu `(x, y)` lên polyline, chọn điểm lookahead **6.5 m** phía trước, tính khoảng cách còn lại dọc theo đường đi (không dùng đường chim bay), và tính chênh lệch tầng từ `hTarget = -anchorRelHeightM`.
   - **Rất gần (< 8 m):** Chuyển sang `VERY_CLOSE`, rung dài xác nhận, thu mũi tên thành chấm sáng `#2EE6A6` nhịp thở và tự mở ngăn kéo ghi chú/ảnh (nếu có).
2. **Giao diện `FindCarScreen` & `ArrowCompass`:**
   - Màn hình tối toàn phần, `FloorBadge` ở trên cùng, mũi tên `Canvas` xoay bằng `spring` theo cung ngắn nhất. Sáng `#2EE6A6` + rung 1 nhịp khi lệch hướng `< 15°`.
   - Nếu la bàn nhiễu (`SENSOR_STATUS_UNRELIABLE` / `LOW`), mũi tên biến thành hình quạt rộng nét đứt kèm nhắc **"Vẽ số 8 để hiệu chỉnh"**.
   - Rung nhịp theo khoảng cách (1.5s khi `> 30m` rút ngắn dần xuống 0.3s khi `< 5m`).

---

## 4. Trạng thái các mốc (Roadmap)

- **M1–M4 (Hoàn thành):** Nền tảng, Room/DataStore, Onboarding/Settings/TrackingService, Auto-Detect (`RecentLocationBuffer`), Dead Reckoning + Debug CSV.
- **M5 (Hoàn thành):** `GuidanceEngine` 2 pha, `ArrowCompass`, `FindCarScreen`, rung theo khoảng cách, modal ghi chú/ảnh & nút **"Đã tìm thấy xe"**.
- **M6 (Chưa làm):** Lịch sử đỗ xe đầy đủ, hiệu chỉnh độ dài bước chân, hoàn thiện & tối ưu pin.
