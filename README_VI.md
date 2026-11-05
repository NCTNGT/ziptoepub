# ZIP → EPUB Studio (Android native)

Đây là **mã nguồn ứng dụng Android**, không phải APK đã biên dịch.

## Tính năng

- Giao diện tiếng Việt, chạy độc lập, không mở trình duyệt và không cần Internet.
- Chọn nhiều ZIP/CBZ từ trình quản lý tệp Android; tự đếm ảnh từng ZIP.
- Đổi tên, xóa, đảo thứ tự và sắp xếp chương theo số trong tên tệp.
- Ảnh bìa và ảnh nền trang đọc được chọn riêng. Nếu chưa có ảnh bìa, lấy ảnh nền làm bìa; nếu chưa có cả hai, tự tạo bìa có tiêu đề.
- Xuất EPUB 3 có mục lục EPUB3 + NCX, chọn đọc từng trang hoặc cuộn dọc theo chương.
- Đọc ZIP theo từng chương, ghi ảnh trực tiếp vào EPUB; không lưu toàn bộ các ảnh trong RAM.
- JPG/JPEG/JFIF, PNG và GIF được giữ nguyên dữ liệu gốc, không resize. WebP/AVIF/BMP được thử giải mã thành PNG ở cùng kích thước pixel, phụ thuộc bộ giải mã Android; ảnh không thể giải mã sẽ gây thông báo lỗi, không bị bỏ qua im lặng.
- Kiểm tra số lượng ảnh trong mỗi ZIP trước khi xuất và xác minh CRC cho ảnh JPG/PNG/GIF.
- Tệp được xử lý nội bộ trên điện thoại. Ứng dụng không yêu cầu quyền Internet.

## Giới hạn

Không có giới hạn cố định trong mã nguồn về số ZIP hoặc số trang truyện. Tuy nhiên:

- Cần đủ dung lượng trống để **sao chép tạm ZIP lớn nhất** trong quá trình ghép và để lưu EPUB đích.
- Chuyển WebP/AVIF/BMP sang PNG cần RAM cho **một ảnh** tại một thời điểm. Ảnh có kích thước pixel cực lớn vẫn có thể thất bại.
- EPUB có hàng nghìn ảnh có thể quá lớn với **ứng dụng đọc EPUB**, ngay cả khi đã tạo thành công.
- Ảnh nền là CSS trong EPUB, không phải trình đọc EPUB nào cũng hiện nền. Ảnh truyện không trong suốt sẽ che mất nền ở vùng có ảnh.
- Tệp ZIP hỏng hoặc ảnh không giải mã được sẽ xuất lỗi thay vì báo thiếu ảnh mà vẫn coi là hoàn tất.
- Chỉ nhập file ZIP/CBZ chứa ảnh (JPG/PNG/GIF/WebP/AVIF/BMP), không hỗ trợ RAR/7z.

## Biên dịch APK bằng Android Studio trên máy tính

Yêu cầu: Android Studio phiên bản hiện đại, JDK 17, Android SDK Platform 35.

1. Giải nén thư mục `ZIP_EPUB_Android_App`.
2. Mở Android Studio → **Open** → chọn thư mục có `settings.gradle`.
3. Đợi Android Studio tải plugin Gradle và SDK (cần mạng lần đầu).
4. Vào **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. File APK debug dự kiến: `app/build/outputs/apk/debug/app-debug.apk`.
6. Chuyển APK sang Android và cài bằng trình quản lý tệp. Nếu Android yêu cầu, cho phép cài ứng dụng từ nguồn đó.

Ghi chú: Trong môi trường tạo gói này **không có Android SDK và không thể tải các công cụ biên dịch**, vì vậy chưa thể xác minh việc build APK thực tế hoặc kiểm thử ứng dụng trên điện thoại.

## Biên dịch APK bằng GitHub Actions (không cần cài Android Studio)

Thư mục dự án đã kèm workflow `.github/workflows/build-apk.yml`.

1. Tạo một repository GitHub mới và tải toàn bộ **nội dung** thư mục dự án lên thư mục gốc repository, giữ cả thư mục `.github`.
2. Mở tab **Actions**, chọn **Build Android APK**, bấm **Run workflow**.
3. Khi quy trình thành công, tải artifact **ZIP-EPUB-Studio-APK** và giải nén để lấy `app-debug.apk`.

GitHub Actions cần tài khoản GitHub và Internet; lần chạy đầu tải Gradle/Android SDK. Có thể có thay đổi tương thích trong các phiên bản tương lai của công cụ biên dịch.

## Mẫu ZIP

```
CHUONG 1.zip
  1.jpg
  2.jpg
  10.png
CHUONG 2.zip
  folder/page_001.jpg
  folder/page_002.webp
```

Ảnh sắp theo số tự nhiên: 1, 2, 10. Mỗi ZIP được coi là một chương; có thể nhập nhiều ZIP cùng lúc.

## Phạm vi kiểm tra

- Logic đóng gói EPUB của `EpubExporter.java` đã được biên dịch và chạy với bộ mô phỏng API Java/Android tối giản để xác nhận mục lục và số ảnh, cả chế độ đọc từng trang lẫn cuộn dọc.
- **Chưa** build và kiểm thử APK trên Android thật. Không khẳng định chạy ổn định ở bất kỳ số ZIP hoặc trang cụ thể nào.
