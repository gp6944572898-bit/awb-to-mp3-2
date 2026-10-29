# AWB → MP3

Android-приложение: пакетно конвертирует `.awb` в `.mp3` и сохраняет в `Музыка/AWB2MP3`.

- Декодирование AWB (AFS2, HCA и др.): [vgmstream](https://github.com/vgmstream/vgmstream) r2168
- Кодирование MP3: LAME 3.100
- Сборка: GitHub Actions (`.github/workflows/build.yml`), готовый APK публикуется в Releases:
  `https://github.com/gp6944572898-bit/awb-to-mp3-2/releases/latest/download/awb2mp3.apk`
