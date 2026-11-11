# Google Colab Pro Remote Build Guide

Hướng dẫn chi tiết quy trình offload toàn bộ quá trình build, test và ký release APK sang Google Colab Pro (sử dụng 25GB tmpfs Ramdisk) nhằm bảo vệ phần cứng máy local (tránh bad sector ổ C, không nghẽn CPU khi đang stream/chơi game).

---

## 1. Yêu cầu & Chuẩn bị
- Đã cài đặt `google-colab-cli`: `pip install google-colab-cli` và đăng nhập `colab auth`.
- SSH Key: `~/.ssh/id_ed25519` (tự động nhận diện).
- File ký release: `keystore_b64.txt` và `keystore.properties` trên thư mục gốc ổ D.

---

## 2. Quy trình 1 lệnh tự động
Quy trình chuẩn gồm các bước:
1. Đóng gói các file thay đổi local thành `patch_all.zip`.
2. Tạo session Colab CPU High-RAM (`colab new -s nyanko-ram --high-mem`).
3. Upload `patch_all.zip`, `keystore_b64.txt`, và script `setup_build.sh` lên `/content/`.
4. Kích hoạt build từ xa:
   - Mount 25GB RAM drive (`/mnt/ramdisk/`).
   - Clone repo vào Ramdisk.
   - Giải nén patch và nạp keystore.
   - Thiết lập Android SDK 35 & build-tools 35.0.0.
   - Chạy `./gradlew test` (178 unit tests pass trong ~11s).
   - Chạy `./gradlew assembleFullRelease` (~50s).
5. Kéo file APK về local: `colab download -s nyanko-ram ...`.
6. Tắt ngay session để bảo toàn credit: `colab stop -s nyanko-ram`.
7. Cài thẳng vào điện thoại: `adb install -r D:\coding\ProjectNyanko\app-full-arm64-v8a-release.apk`.
