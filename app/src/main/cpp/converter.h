#ifndef AWB_CONVERTER_H
#define AWB_CONVERTER_H

/* Количество подпотоков (треков) в файле; всегда >= 1. Отрицательное значение — ошибка. */
int awb_count(const char* path);

/* Декодирует подпоток subsong (1..N, 0 = первый) в MP3-файл out_path.
 * Возвращает 0 при успехе, иначе < 0 и текст ошибки в err. */
int awb_convert(const char* path, int subsong, const char* out_path, int bitrate_kbps,
                char* err, int err_len);

/* Текст диагностики для файла, который не открылся */
void awb_diag(const char* path, char* out, int out_len);

/* Потоковый кодировщик MP3: звук подаётся порциями (PCM 16 бит, little-endian, каналы чередуются).
 * Нужен для файлов, которые декодирует сама система Android (например, AMR-WB):
 * так не требуются огромные временные файлы для многочасовых аудиокниг. */
typedef struct awb_enc awb_enc_t;

awb_enc_t* awb_enc_open(const char* out_path, int sample_rate, int channels, int bitrate_kbps);
int awb_enc_write(awb_enc_t* enc, const unsigned char* data, int bytes); /* 0 — ок, < 0 — ошибка */
int awb_enc_close(awb_enc_t* enc);                                       /* дописывает хвост и закрывает */

#endif
