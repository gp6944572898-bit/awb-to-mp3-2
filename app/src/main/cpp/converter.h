#ifndef AWB_CONVERTER_H
#define AWB_CONVERTER_H

/* Количество подпотоков (треков) в файле; всегда >= 1. Отрицательное значение — ошибка. */
int awb_count(const char* path);

/* Декодирует подпоток subsong (1..N, 0 = первый) в MP3-файл out_path.
 * Возвращает 0 при успехе, иначе < 0 и текст ошибки в err. */
int awb_convert(const char* path, int subsong, const char* out_path, int bitrate_kbps,
                char* err, int err_len);

#endif
