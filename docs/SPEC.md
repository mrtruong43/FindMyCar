# Spec kỹ thuật: Đỗ xe thông minh (FindMyCar)

*Dành cho tác tử AI trong Antigravity. Thiết bị đích: Samsung Galaxy Z Flip6. Chỉ một module Android `app`, không Wear OS.*

## 1. Mục tiêu và phạm vi

**Vấn đề:** người dùng đỗ xe trong hầm (mất GPS), cần quay lại đúng xe.

**Giải pháp:** tự phát hiện lúc đỗ xe, lưu vị trí, tầng, ghi chú, ảnh. Trong lúc người dùng đi bộ ra khỏi xe, app ghi lại "dấu chân" (dead reckoning). Khi quay lại, app dẫn đường ngược theo dấu chân bằng mũi tên, khoảng cách còn lại và số tầng cần đi.

**Trong phạm vi:** phát hiện đỗ xe, lưu và ghi chú, ghi dấu chân, mũi tên quay về, ước tính tầng, lịch sử, cài đặt.

**Ngoài phạm vi (làm sau):** màn hình ngoài của Flip6, tab tai nghe, đồng bộ đám mây, Wear OS.

**Nguyên tắc:** mọi dữ liệu lưu cục bộ, không cần tài khoản, không cần API key. Ngôn ngữ giao diện: tiếng Việt.

## 2. Công nghệ và ràng buộc

- Kotlin, Jetpack Compose (Material 3), minSdk 26, targetSdk 35.
- Kiến trúc: MVVM, một Activity, Navigation Compose, Kotlin Coroutines và Flow.
- Lưu trữ: Room (dùng KSP), DataStore cho cài đặt.
- Vị trí: `play-services-location` (FusedLocationProviderClient) và Activity Recognition Transition API.
- Cảm biến: `SensorManager` (rotation vector, step detector, pressure, linear acceleration).
- Ảnh: `ActivityResultContracts.TakePicture` kèm `FileProvider`. Ghi chú giọng nói: `RecognizerIntent` với `vi-VN`.
- Bản đồ: **không dùng Maps SDK** (tránh API key). Mở bản đồ ngoài bằng intent `geo:`.
- Dùng version catalog (`libs.versions.toml`), chọn phiên bản ổn định mới nhất tương thích Android Studio/AGP hiện có.
- Không dùng thư viện ngoài khi API chuẩn đủ dùng.

## 3. Cấu trúc package

```
com.yourname.findmycar
├── MainActivity.kt
├── data/
│   ├── db/ (AppDatabase, ParkingSpotDao, TrackPointDao, Entities)
│   ├── ParkingRepository.kt
│   └── SettingsStore.kt
├── detect/
│   ├── ParkingDetector.kt        // xử lý sự kiện đỗ xe
│   ├── ActivityTransitionReceiver.kt
│   └── RecentLocationBuffer.kt   // đệm vị trí khi đang lái
├── tracking/
│   ├── TrackingService.kt        // foreground service
│   ├── HeadingEstimator.kt
│   ├── StepTracker.kt
│   ├── PressureAltimeter.kt
│   └── PathModel.kt              // hình học dấu chân
├── guidance/
│   ├── GuidanceEngine.kt         // tính mũi tên, khoảng cách, tầng
│   └── CircularFilter.kt
├── ui/
│   ├── home/, detail/, find/, history/, settings/
│   └── components/ (ArrowCompass, FloorBadge)
└── util/ (Permissions, GeoMath)
```

## 4. Mô hình dữ liệu (Room)

**ParkingSpot**

- `id: Long` (PK, tự tăng)
- `savedAt: Long` (epoch ms)
- `lat, lon: Double?`, `accuracyM: Float?` (GPS cuối cùng tốt trước khi mất sóng)
- `pressureHpa: Float?` (áp suất lúc rời xe)
- `note: String`, `photoUri: String?`
- `source: String` (`AUTO_ACTIVITY`, `MANUAL`)
- `status: String` (`ACTIVE`, `FOUND`, `ARCHIVED`)
- `anchorSet: Boolean`, `anchorLat, anchorLon: Double?`, `anchorPressureHpa: Float?` (điểm neo, xem mục 7)
- `anchorRelHeightM: Float?` (độ cao của điểm neo so với xe, tính lúc đặt neo)

**TrackPoint**

- `id: Long`, `spotId: Long` (FK, xóa theo spot)
- `seq: Int`
- `x: Float`, `y: Float` (mét, hệ tọa độ phẳng: x hướng Đông từ la bàn, y hướng Bắc từ la bàn, gốc là vị trí xe)
- `relHeightM: Float`
- `t: Long`

Chỉ cho phép đúng một spot `ACTIVE` tại một thời điểm. Spot mới được lưu thì spot cũ chuyển `ARCHIVED`.

## 5. Quyền và Manifest

- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION` (xin riêng, sau khi quyền thường được cấp, kèm màn hình giải thích)
- `ACTIVITY_RECOGNITION` (cũng cần cho step detector)
- `POST_NOTIFICATIONS`
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`
- `CAMERA` không cần khai báo nếu dùng `TakePicture` qua FileProvider
- Khai báo `TrackingService` với `foregroundServiceType="location"`; khai báo các receiver.

Quy trình xin quyền: màn hình onboarding giải thích từng quyền bằng tiếng Việt, xin lần lượt, app vẫn chạy ở chế độ "lưu thủ công" nếu người dùng từ chối quyền nền.

## 6. Luồng phát hiện đỗ xe

### 6.1 Đệm vị trí khi đang lái

GPS thường mất ngay khi xe xuống hầm, nên lúc phát hiện đỗ thì không còn vị trí tốt. Giải pháp:

1. Đăng ký Activity Transition: `IN_VEHICLE` ENTER và EXIT, `WALKING` ENTER.
2. Khi vào `IN_VEHICLE`, bật cập nhật vị trí mức cân bằng (khoảng 5-10 giây một lần) trong `RecentLocationBuffer`, giữ khoảng 5 phút gần nhất.
3. Khi ra khỏi `IN_VEHICLE`, tắt cập nhật vị trí để tiết kiệm pin.

### 6.2 Sự kiện đỗ xe

Kích hoạt khi một trong các tín hiệu sau xảy ra:

- `IN_VEHICLE` EXIT rồi `WALKING` ENTER trong vòng 2 phút.
- Người dùng bấm nút "Lưu xe" (thủ công).

Chống kích hoạt trùng: bỏ qua sự kiện mới nếu trong 90 giây đã có một sự kiện đỗ xe.

### 6.3 Hành động khi đỗ xe

1. Lấy vị trí tốt nhất từ buffer (độ chính xác dưới 50 m, mới nhất). Nếu không có thì thử `getCurrentLocation` timeout 5 giây, vẫn không có thì lưu `lat/lon = null`.
2. Đọc áp suất hiện tại (lấy trung bình khoảng 2 giây).
3. Tạo `ParkingSpot` mới, gốc tọa độ của dấu chân là vị trí này.
4. Khởi động `TrackingService` để ghi dấu chân.
5. Gửi thông báo: "Đã lưu vị trí xe. Chạm để thêm ghi chú hoặc ảnh."

## 7. Ghi dấu chân (dead reckoning)

### 7.1 Ý tưởng

Chỉ lưu **đường đi tương đối từ xe**, không cố định vị tuyệt đối. Vì cả lúc đi ra và lúc đi về đều đo hướng bằng la bàn của cùng một thiết bị, nên **độ lệch từ trường (declination) tự triệt tiêu**, chỉ còn nhiễu cục bộ trong hầm.

### 7.2 Phát hiện bước

- Dùng `TYPE_STEP_DETECTOR` (đăng ký với batching nếu có).
- Mỗi sự kiện bước cộng một đoạn dài `stepLength` theo hướng đi hiện tại.
- `stepLength` mặc định `0.415 × chiều cao`, người dùng nhập chiều cao trong Cài đặt (mặc định 1,65 m, tức khoảng 0,68 m). Có chế độ hiệu chỉnh: đi một quãng đã biết (ví dụ 20 m) rồi nhập số đo thực để app tự điều chỉnh.

### 7.3 Ước tính hướng đi

Hai chế độ, chọn tự động:

**Chế độ A, cầm máy trước người (ưu tiên khi quay về):** dùng `TYPE_ROTATION_VECTOR`, lấy `getRotationMatrix` rồi `getOrientation` để có azimuth của máy. Hướng đi bằng azimuth (cộng độ lệch cố định nếu người dùng cầm máy nghiêng, bỏ qua ở bản đầu).

**Chế độ B, máy trong túi (khi ghi dấu chân lúc ra khỏi xe):** azimuth của máy không phải hướng cơ thể. Cách làm:

1. Dùng `TYPE_LINEAR_ACCELERATION`, xoay vector gia tốc về hệ tọa độ thế giới bằng ma trận quay.
2. Trong cửa sổ trượt khoảng 2 giây, lấy thành phần nằm ngang (hai trục thế giới) rồi tìm trục chính của dao động bằng PCA (hoặc trị riêng lớn nhất của ma trận hiệp phương sai 2x2).
3. Trục chính cho ra hướng đi với mơ hồ 180°. Phân giải bằng dấu của gia tốc theo hướng đó ngay thời điểm chạm gót (đỉnh gia tốc dọc), hoặc bằng chiều thay đổi của chuỗi vị trí.
4. Dùng `CircularFilter` (lọc thông thấp trên góc, làm trên sin/cos) để làm mượt.

Ghi chú: nếu độ tin cậy PCA thấp (tỉ lệ hai trị riêng gần nhau) thì giữ hướng cũ thay vì cập nhật.

### 7.4 Cập nhật vị trí

Mỗi bước: `x += L * sin(θ)`, `y += L * cos(θ)` với θ là hướng so với Bắc từ la bàn, L là độ dài bước. Lưu `TrackPoint` mỗi bước, nhưng ghi DB theo lô (mỗi 10 điểm hoặc 5 giây) để tiết kiệm.

### 7.5 Cao độ từ khí áp

- `TYPE_PRESSURE`, lọc trung bình trượt khoảng 1-2 giây rồi lọc thông thấp.
- Độ cao tương đối so với xe: `h = 44330 × (1 − (p / p_car)^(1/5.255))` với `p_car` là áp suất lúc rời xe.
- Số tầng = `round(h / floorHeight)`, `floorHeight` mặc định 3,2 m, chỉnh được trong Cài đặt.
- Chỉ tin dấu hiệu thay đổi **tương đối trong vài phút**. Áp suất trôi theo thời tiết và điều hòa, nên không so với mốc đo từ nhiều giờ trước.

### 7.6 Điểm neo và kết thúc ghi

Dấu chân chỉ có ý nghĩa nếu nối được với vị trí biết trước khi quay về. Dùng **điểm neo** (thường là thang máy hoặc cửa ra hầm):

- Kết thúc ghi tự động khi: GPS có fix tốt (dưới 20 m) liên tục khoảng 20 giây, **hoặc** người dùng bấm "Tôi đã tới lối ra/thang máy", **hoặc** quá 30 phút.
- Khi kết thúc: lưu `anchorLat/anchorLon` nếu có GPS, `anchorPressureHpa`, và `anchorRelHeightM` (độ cao của điểm neo so với xe, tính trong vài phút nên đáng tin).
- Dừng `TrackingService`, gỡ notification ghi.

Trong lúc ghi, `TrackingService` cần: foreground notification "Đang ghi đường đi từ xe", giữ `PARTIAL_WAKE_LOCK` ngắn hạn nếu cần, hạ tần suất lấy mẫu khi đứng yên (không có bước trong 10 giây).

## 8. Dẫn đường quay về (GuidanceEngine)

Hai pha:

**Pha 1, tới điểm neo (ngoài trời, có GPS):** dùng bearing và khoảng cách từ GPS hiện tại tới `anchorLat/anchorLon` (hoặc tới `lat/lon` của xe nếu chưa có neo). Mũi tên = bearing trừ azimuth của máy. Khi cách neo dưới 15 m hoặc người dùng bấm "Tôi đang ở lối ra/thang máy" thì chuyển pha 2 và đặt lại áp suất tham chiếu tại chỗ đó.

**Pha 2, theo dấu chân (trong hầm):**

1. Khi vào pha 2, đặt vị trí hiện tại = điểm cuối của đường dấu chân (điểm neo), bắt đầu ghi một dead reckoning mới (cùng cách tính mục 7) với gốc là điểm này.
2. Đường cần đi = dấu chân đảo ngược (từ neo về xe) dưới dạng polyline.
3. Chiếu vị trí hiện tại lên polyline để biết đang ở đoạn nào. Chọn điểm đích tạm cách điểm chiếu khoảng 5-8 m (lookahead) dọc theo polyline.
4. Mũi tên = bearing tới điểm lookahead trừ azimuth của máy, làm mượt bằng `CircularFilter`.
5. Khoảng cách còn lại = độ dài polyline còn lại (không dùng đường chim bay).
6. Tầng: `hTarget = −anchorRelHeightM` so với vị trí hiện tại, tính từ áp suất đã đặt lại ở pha 2. Hiển thị "Xuống 2 tầng" / "Lên 1 tầng" / "Đúng tầng".

Khi khoảng cách còn lại dưới 8 m: đổi giao diện sang "Rất gần", rung nhẹ, nhắc xem ghi chú và ảnh. Có nút "Đã tìm thấy xe" để đóng spot (`FOUND`).

Hiệu chỉnh la bàn: lắng nghe `onAccuracyChanged`; khi độ chính xác thấp thì hiện hướng dẫn "vẽ số 8 bằng điện thoại".

## 9. Màn hình

1. **Home:** thẻ spot đang hoạt động (thời gian đỗ, tầng ước tính, ghi chú), nút lớn **Lưu xe**, nút **Tìm xe**, trạng thái dịch vụ.
2. **Chi tiết spot:** ghi chú văn bản, nút giọng nói, chụp ảnh, xem ảnh, nút "Mở trên bản đồ" (intent `geo:`), chia sẻ vị trí.
3. **Tìm xe:** `ArrowCompass` xoay theo mũi tên, khoảng cách (mét), huy hiệu tầng (`FloorBadge`), nút "Tôi đang ở lối ra/thang máy", nút "Đã tìm thấy xe", nhắc ghi chú và ảnh ở dưới.
4. **Lịch sử:** danh sách spot cũ, xóa, xem lại.
5. **Cài đặt:** bật/tắt tự phát hiện, chiều cao và độ dài bước, độ cao tầng, hiệu chỉnh bước, mục xóa dữ liệu, hướng dẫn tắt tối ưu pin cho Samsung.
6. **Debug (ẩn, bật bằng cách bấm 7 lần vào phiên bản):** ghi log cảm biến và dấu chân ra CSV để phân tích, xem số bước, hướng, áp suất, độ tin cậy PCA.

UI nhỏ gọn, chữ lớn, tương phản cao, vì người dùng nhìn nhanh lúc đang đi. Hỗ trợ cả trạng thái gập và mở của Flip6 (layout co giãn, không phụ thuộc kích thước cố định).

## 9A. Giao diện và luồng người dùng

Phong cách: hiện đại, tối giản, hơi "tương lai", lấy cảm hứng từ màn hình Precision Finding của iOS: nền tối, một yếu tố chính ở giữa màn hình (mũi tên và con số khoảng cách), chuyển động mượt, gần như không có chữ thừa. Mọi màn hình ở M1 đến M6 tuân theo mục này.

### Luồng chính

1. **Lần đầu mở app:** 3 màn onboarding ngắn (app làm gì, xin quyền vị trí và hoạt động, hướng dẫn tắt tối ưu pin Samsung). Có thể bỏ qua, app vẫn dùng được ở chế độ lưu thủ công.
2. **Đỗ xe:** app tự lưu (hoặc bấm **Lưu xe**). Thông báo hiện "Đã lưu vị trí xe" kèm hai nút nhanh: **Thêm ghi chú**, **Chụp ảnh**.
3. **Đi ra khỏi xe:** app ghi dấu chân nền. Mở app thấy thẻ "Đang ghi đường đi" và nút **Tôi đã tới lối ra/thang máy** để chốt điểm neo.
4. **Quay lại:** mở app, bấm **Tìm xe**. Pha 1 dẫn tới điểm neo bằng GPS, pha 2 dẫn theo dấu chân vào hầm.
5. **Gần xe:** màn hình chuyển sang chế độ "Rất gần", nhắc xem ghi chú và ảnh.
6. **Tìm thấy:** bấm **Đã tìm thấy xe**, spot chuyển sang lịch sử.

### Màn hình Tìm xe (màn quan trọng nhất)

Toàn màn hình, không thanh điều hướng, một thứ nổi bật duy nhất tại một thời điểm.

- **Trên cùng:** viên thuốc nhỏ (pill) báo tầng, ví dụ "Xuống 2 tầng" hoặc "Đúng tầng". Nút đóng nhỏ ở góc.
- **Giữa màn hình:** mũi tên lớn, đường nét mảnh, xoay theo hướng cần đi. Xung quanh là 2 đến 3 vòng tròn đồng tâm mờ. Khi mũi tên lệch hướng cần đi dưới 15 độ, mũi tên và vòng ngoài sáng lên màu nhấn và máy rung nhẹ một nhịp (giống tín hiệu "đúng hướng" của iOS).
- **Dưới mũi tên:** con số khoảng cách rất lớn, chữ mảnh, đơn vị "m" nhỏ hơn. Số đổi bằng hiệu ứng cuộn chữ số, không nhảy cục.
- **Dòng trạng thái:** một dòng chữ nhỏ mờ: "Đi thẳng", "Rẽ trái", "Tín hiệu yếu".
- **Dưới cùng (vùng ngón cái với tới):** hai nút bo tròn, nền kính mờ: **Ghi chú và ảnh** (mở ngăn kéo từ dưới lên) và **Đã tìm thấy xe**.
- **Độ tin cậy thấp** (la bàn nhiễu hoặc sai số lớn): mũi tên biến thành hình quạt (cone) rộng, độ rộng tỉ lệ với sai số, vòng đồng tâm chuyển sang nét đứt.
- **Dưới 8 m:** mũi tên thu lại thành một chấm sáng nhịp thở, chữ "Rất gần", ghi chú và ảnh tự trượt lên nhẹ.

Rung theo khoảng cách: chu kỳ khoảng 1,5 giây khi trên 30 m, rút ngắn dần xuống khoảng 0,3 giây khi dưới 5 m. Có công tắc tắt rung trong Cài đặt.

### Màn hình Home

Một màn duy nhất, không thanh điều hướng dưới. Icon Lịch sử và Cài đặt ở góc trên bên phải.

- **Có xe đang lưu:** thẻ lớn ở giữa: "Xe của bạn", đỗ cách đây bao lâu, tầng ước tính, ảnh thu nhỏ nếu có. Nút **Tìm xe** to, màu nhấn, nằm ở nửa dưới màn hình.
- **Chưa có xe:** chỉ một vòng tròn lớn, nét mảnh, chữ "Lưu xe" ở giữa; bấm vào là lưu.
- Nút **Lưu xe** thủ công luôn có sẵn dạng nhỏ khi đã có xe (để ghi đè khi đỗ lại).

### Các trạng thái cần thiết kế

| Trạng thái | Hiển thị | Hành vi |
| --- | --- | --- |
| Chưa có xe | Vòng tròn "Lưu xe" | Bấm để lưu thủ công |
| Đang ghi dấu chân | Thẻ "Đang ghi đường đi" kèm số bước | Nút chốt điểm neo, tự dừng sau 30 phút |
| Đã đỗ, chưa có điểm neo | Thẻ xe, nhãn "Chưa chốt lối ra" | Tìm xe vẫn dùng được, chỉ dựa GPS và ghi chú |
| Tìm xe pha 1 (GPS) | Mũi tên, khoảng cách tới điểm neo | Chuyển pha 2 khi cách neo dưới 15 m |
| Tìm xe pha 2 (dấu chân) | Mũi tên, khoảng cách theo đường đi, huy hiệu tầng | Cập nhật theo từng bước chân |
| La bàn nhiễu | Mũi tên dạng hình quạt rộng, dòng "Vẽ số 8 để hiệu chỉnh" | Hiện hướng dẫn hiệu chỉnh |
| Không có GPS và không có dấu chân | Màn hình chỉ có ghi chú, ảnh, tầng | Báo rõ lý do |
| Rất gần xe | Chấm sáng nhịp thở, "Rất gần" | Gợi ý xem ảnh và ghi chú |
| Thiếu quyền | Thẻ giải thích kèm nút "Cấp quyền" | Mở thẳng màn hình cài đặt quyền |
| Máy không có khí áp kế | Ẩn huy hiệu tầng | Dòng thông báo nhỏ một lần |

### Quy tắc thiết kế

| Thành phần | Quy định |
| --- | --- |
| Chế độ màu | Tối là mặc định (nền gần đen, không dùng đen tuyền nhiều lớp), có chế độ sáng theo hệ thống. Tắt dynamic color của Android để giữ bộ nhận diện riêng |
| Màu nhấn | Một màu duy nhất, sáng và bão hòa (gợi ý xanh ngọc `#2EE6A6`), dùng cho mũi tên khi đúng hướng, nút chính, số tầng. Cảnh báo dùng màu cam, lỗi dùng đỏ, ít dùng |
| Chữ | Phông hệ thống của One UI. Con số khoảng cách rất lớn, nét Thin hoặc Light; nhãn nhỏ nét Regular, màu xám mờ. Tối đa 3 cỡ chữ trong mỗi màn hình |
| Hình khối | Bo tròn lớn (nút dạng viên thuốc, thẻ bo khoảng 28 dp), nền kính mờ cho lớp nổi, bóng đổ rất nhẹ |
| Biểu tượng | Nét mảnh đồng nhất, vẽ bằng Compose Canvas hoặc icon vector, không dùng ảnh bitmap |
| Chuyển động | Mũi tên xoay bằng spring (giảm chấn nhẹ), luôn xoay theo cung ngắn nhất, không giật. Chuyển màn bằng fade và scale nhẹ, 200 đến 300 ms |
| Rung | Một nhịp khi đúng hướng, nhịp lặp theo khoảng cách (xem trên), một nhịp dài khi tới nơi |
| Chạm | Vùng bấm tối thiểu 56 dp, nút chính nằm nửa dưới màn hình để dùng một tay, kể cả khi đeo găng |
| Khả năng tiếp cận | Nội dung đọc được bằng TalkBack ("Xe cách 47 mét, đi thẳng, xuống 2 tầng"), tỉ lệ tương phản đạt chuẩn WCAG AA |
| Ngôn ngữ | Câu ngắn, không thuật ngữ kỹ thuật (không nói "dead reckoning", "neo" chỉ hiện ở dạng "lối ra/thang máy") |

Ghi chú triển khai: toàn bộ mũi tên, vòng tròn và hình quạt vẽ bằng `Canvas` trong Compose, dùng `animateFloatAsState` với `spring` cho góc xoay, và lọc góc bằng `CircularFilter` trước khi đưa vào animation để mũi tên không rung.

## 10. Samsung và nền hệ thống

- Samsung (One UI) dễ dừng app nền. Trong Cài đặt có màn hình hướng dẫn người dùng: tắt tối ưu hóa pin cho app, thêm vào danh sách "Ứng dụng không bao giờ ngủ" (Never sleeping apps).
- Yêu cầu bỏ tối ưu pin bằng intent `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` kèm giải thích.
- Đăng ký lại Activity Transition sau khởi động lại máy (`BOOT_COMPLETED` receiver).
- Mọi thao tác trong receiver phải ngắn, việc nặng đẩy vào service.

## 11. Xử lý lỗi và trường hợp biên

- Không có GPS hoặc quyền bị từ chối: vẫn lưu spot với `lat/lon = null`, vẫn ghi dấu chân và tầng.
- Điện thoại không có khí áp kế (hiếm với Flip6): ẩn phần tầng, thông báo rõ.
- Người dùng đỗ xe rồi không đi bộ ra (ở lại trong xe): chỉ ghi khi phát hiện `WALKING`.
- Quá 30 phút ghi hoặc pin dưới 10 phần trăm: tự dừng ghi và lưu những gì đã có.
- Spot thủ công không có dấu chân: pha dẫn đường chỉ dùng GPS và ghi chú, hiển thị đúng thông báo.
- Xóa dữ liệu: xóa cả file ảnh.

## 12. Thứ tự triển khai và tiêu chí hoàn thành

Làm lần lượt, **mỗi mốc phải build chạy được trước khi sang mốc sau**.

**M1: Nền tảng và lưu thủ công**

- Project, version catalog, Room, DataStore, Navigation, Home, Chi tiết spot.
- Hoàn thành khi: bấm Lưu xe thì lưu GPS, áp suất và ghi chú; thoát app mở lại vẫn thấy; chụp ảnh và nhập giọng nói hoạt động; "Mở trên bản đồ" mở đúng vị trí.

**M2: Quyền và dịch vụ nền**

- Onboarding quyền, `TrackingService` foreground, hướng dẫn tối ưu pin Samsung.
- Hoàn thành khi: dịch vụ chạy ổn trong 10 phút với màn hình tắt, không bị hệ thống giết.

**M3: Phát hiện đỗ xe tự động**

- Activity Transition, `RecentLocationBuffer`, chống kích hoạt trùng.
- Hoàn thành khi: lái xe thật rồi đỗ thì app tự lưu spot với GPS đúng (độ lệch dưới 50 m so với chỗ đỗ).

**M4: Ghi dấu chân**

- `StepTracker`, `HeadingEstimator` (chế độ A và B), `PressureAltimeter`, `PathModel`, điểm neo, Debug CSV.
- Hoàn thành khi: đi vòng một đường khép kín khoảng 100 m, điểm kết thúc lệch điểm xuất phát dưới 15 m; đi lên 1 tầng thang bộ thì tầng ước tính tăng đúng 1.

**M5: Dẫn đường quay về**

- `GuidanceEngine`, `ArrowCompass`, hai pha, tầng, rung gần xe.
- Hoàn thành khi: sau M4, đi tới neo rồi quay về theo mũi tên, dừng cách xe dưới 15 m và nhìn thấy xe hoặc ghi chú đủ để xác định.

**M6: Hoàn thiện**

- Lịch sử, Cài đặt đầy đủ, hiệu chỉnh bước, xử lý lỗi, giảm pin.
- Hoàn thành khi: dùng liên tục một tuần không lỗi nghiêm trọng, pin ghi dấu chân dưới khoảng 3 phần trăm cho mỗi lần đỗ.

## 13. Kiểm thử

- **Unit test (không cần máy):** `GeoMath`, `CircularFilter`, `PathModel` (độ dài polyline, chiếu điểm, đảo đường), công thức độ cao, PCA hướng đi với dữ liệu tổng hợp.
- **Test trên máy thật (người dùng làm):** đi vòng khép kín ngoài sân, thử ở hầm thật, thử máy trong túi và cầm tay, thử tắt màn hình.
- **Log:** mọi hiệu chỉnh thuật toán dựa trên CSV từ màn hình Debug, không đoán.

## 14. Hướng dẫn làm việc cho tác tử

- Làm đúng **một mốc mỗi lần**, build và báo cáo trước khi sang mốc kế tiếp.
- Mỗi mốc kết thúc bằng: danh sách file đã tạo hoặc sửa, các bước để người dùng test trên máy thật, và các câu hỏi còn mở.
- Không thêm thư viện hoặc tính năng ngoài spec khi chưa hỏi.
- Khi gặp xung đột phiên bản thư viện, chọn bản ổn định tương thích với AGP hiện tại và ghi lại lý do.
- Mọi chuỗi giao diện đặt trong `strings.xml` (tiếng Việt).
- Ưu tiên code rõ ràng, tách thuật toán thuần (không phụ thuộc Android) để unit test được.

### Prompt khởi động đề xuất

> Đọc kỹ spec này. Tạo project Android mới (Kotlin, Compose, minSdk 26, package `com.yourname.findmycar`) và chỉ làm **mốc M1**. Dùng version catalog, Room với KSP, DataStore, Navigation Compose. Xong thì build, liệt kê file đã tạo, hướng dẫn tôi test trên Galaxy Z Flip6, rồi dừng chờ tôi xác nhận trước khi sang M2.
