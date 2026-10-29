#include "converter.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "libvgmstream.h"
#include "lame.h"

/* Буфер для сообщений vgmstream (нужен для диагностики) */
static char g_log[1024];

static void log_cb(int level, const char* str) {
    size_t used = strlen(g_log);
    if (used + 2 >= sizeof(g_log) || !str)
        return;
    snprintf(g_log + used, sizeof(g_log) - used, "%s\n", str);
}

static void log_start(void) {
    g_log[0] = 0;
    libvgmstream_set_log(LIBVGMSTREAM_LOG_LEVEL_ALL, log_cb);
}

static void set_err(char* err, int err_len, const char* msg) {
    if (err && err_len > 0)
        snprintf(err, (size_t)err_len, "%s", msg);
}

/* Открывает файл через vgmstream; sf закрывается сразу после открытия (так требует API) */
static libvgmstream_t* open_lib(const char* path, int subsong, libvgmstream_config_t* cfg) {
    libstreamfile_t* sf = libstreamfile_open_from_stdio(path);
    if (!sf)
        return NULL;
    libvgmstream_t* lib = libvgmstream_create(sf, subsong, cfg);
    libstreamfile_close(sf);
    return lib;
}

int awb_count(const char* path) {
    log_start();

    libvgmstream_t* lib = open_lib(path, 0, NULL);
    if (!lib)
        return -1;

    int count = lib->format->subsong_count;
    libvgmstream_free(lib);
    return count > 0 ? count : 1;
}

int awb_convert(const char* path, int subsong, const char* out_path, int bitrate_kbps,
                char* err, int err_len) {
    int result = -1;
    FILE* out = NULL;
    lame_t lame = NULL;
    unsigned char* mp3_buf = NULL;
    size_t mp3_buf_size = 0;
    libvgmstream_t* lib = NULL;

    log_start();

    libvgmstream_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.ignore_loop = true;                          /* один проход без зацикливания */
    cfg.loop_count = 1;
    cfg.auto_downmix_channels = 2;                   /* MP3 — максимум стерео */
    cfg.force_sfmt = LIBVGMSTREAM_SFMT_PCM16;

    lib = open_lib(path, subsong, &cfg);
    if (!lib) {
        set_err(err, err_len, "Формат не распознан или файл повреждён (возможно, HCA зашифрован неизвестным ключом)");
        awb_diag(path, err, err_len);
        goto done;
    }

    int channels = lib->format->channels;
    int rate = lib->format->sample_rate;
    if (channels < 1 || channels > 2 || rate <= 0) {
        set_err(err, err_len, "Неподдерживаемое число каналов");
        goto done;
    }

    out = fopen(out_path, "wb");
    if (!out) {
        set_err(err, err_len, "Не удалось создать временный файл");
        goto done;
    }

    lame = lame_init();
    if (!lame) {
        set_err(err, err_len, "Не удалось инициализировать LAME");
        goto done;
    }
    lame_set_in_samplerate(lame, rate);
    lame_set_num_channels(lame, channels);
    lame_set_mode(lame, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(lame, bitrate_kbps > 0 ? bitrate_kbps : 192);
    lame_set_quality(lame, 5);
    lame_set_bWriteVbrTag(lame, 0);
    if (lame_init_params(lame) < 0) {
        set_err(err, err_len, "LAME: неверные параметры кодирования");
        goto done;
    }

    for (;;) {
        int r = libvgmstream_render(lib);
        if (r < 0) {
            set_err(err, err_len, "Ошибка декодирования");
            goto done;
        }

        libvgmstream_decoder_t* dec = lib->decoder;
        int n = dec->buf_samples;
        if (n > 0) {
            size_t need = (size_t)(1.25 * n + 7200);
            if (need > mp3_buf_size) {
                unsigned char* p = realloc(mp3_buf, need);
                if (!p) {
                    set_err(err, err_len, "Недостаточно памяти");
                    goto done;
                }
                mp3_buf = p;
                mp3_buf_size = need;
            }

            short* pcm = (short*)dec->buf;
            int bytes;
            if (channels == 1)
                bytes = lame_encode_buffer(lame, pcm, pcm, n, mp3_buf, (int)mp3_buf_size);
            else
                bytes = lame_encode_buffer_interleaved(lame, pcm, n, mp3_buf, (int)mp3_buf_size);

            if (bytes < 0) {
                set_err(err, err_len, "Ошибка кодирования MP3");
                goto done;
            }
            if (bytes > 0 && fwrite(mp3_buf, 1, (size_t)bytes, out) != (size_t)bytes) {
                set_err(err, err_len, "Ошибка записи (нет места?)");
                goto done;
            }
        }

        if (dec->done)
            break;
    }

    if (mp3_buf_size < 7200) {
        mp3_buf = realloc(mp3_buf, 7200);
        mp3_buf_size = 7200;
    }
    if (mp3_buf) {
        int bytes = lame_encode_flush(lame, mp3_buf, (int)mp3_buf_size);
        if (bytes > 0)
            fwrite(mp3_buf, 1, (size_t)bytes, out);
    }
    result = 0;

done:
    if (lame)
        lame_close(lame);
    if (out)
        fclose(out);
    if (lib)
        libvgmstream_free(lib);
    free(mp3_buf);
    return result;
}

/* Диагностика: размер файла, первые байты (заголовок) и сообщения vgmstream */
void awb_diag(const char* path, char* out, int out_len) {
    if (!out || out_len <= 0)
        return;
    FILE* f = fopen(path, "rb");
    if (!f) {
        snprintf(out, (size_t)out_len, "не удалось открыть файл");
        return;
    }
    unsigned char head[16] = {0};
    size_t got = fread(head, 1, sizeof(head), f);
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fclose(f);

    char hex[64] = {0};
    char txt[20] = {0};
    for (size_t i = 0; i < got; i++) {
        snprintf(hex + i * 3, sizeof(hex) - i * 3, "%02X ", head[i]);
        txt[i] = (head[i] >= 32 && head[i] < 127) ? (char)head[i] : '.';
    }
    snprintf(out, (size_t)out_len, "размер=%ld, заголовок: %s| \"%s\" | лог: %s",
             size, hex, txt, g_log[0] ? g_log : "(пусто)");
}

int awb_encode_pcm(const char* pcm_path, int sample_rate, int channels, const char* out_path,
                   int bitrate_kbps, char* err, int err_len) {
    int result = -1;
    FILE* in = NULL;
    FILE* out = NULL;
    lame_t lame = NULL;
    short* pcm = NULL;
    unsigned char* mp3_buf = NULL;
    const int chunk = 4096; /* кадров за один проход */
    const int mp3_size = (int)(1.25 * chunk + 7200);

    if (channels < 1 || channels > 2 || sample_rate <= 0) {
        set_err(err, err_len, "Неподдерживаемые параметры звука");
        return -1;
    }

    in = fopen(pcm_path, "rb");
    out = fopen(out_path, "wb");
    pcm = malloc((size_t)chunk * (size_t)channels * sizeof(short));
    mp3_buf = malloc((size_t)mp3_size);
    if (!in || !out || !pcm || !mp3_buf) {
        set_err(err, err_len, "Не удалось открыть временные файлы");
        goto done;
    }

    lame = lame_init();
    if (!lame) {
        set_err(err, err_len, "Не удалось инициализировать LAME");
        goto done;
    }
    lame_set_in_samplerate(lame, sample_rate);
    lame_set_num_channels(lame, channels);
    lame_set_mode(lame, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(lame, bitrate_kbps > 0 ? bitrate_kbps : 192);
    lame_set_quality(lame, 5);
    lame_set_bWriteVbrTag(lame, 0);
    if (lame_init_params(lame) < 0) {
        set_err(err, err_len, "LAME: неверные параметры кодирования");
        goto done;
    }

    for (;;) {
        size_t frames = fread(pcm, sizeof(short) * (size_t)channels, (size_t)chunk, in);
        if (frames == 0)
            break;
        int bytes;
        if (channels == 1)
            bytes = lame_encode_buffer(lame, pcm, pcm, (int)frames, mp3_buf, mp3_size);
        else
            bytes = lame_encode_buffer_interleaved(lame, pcm, (int)frames, mp3_buf, mp3_size);
        if (bytes < 0) {
            set_err(err, err_len, "Ошибка кодирования MP3");
            goto done;
        }
        if (bytes > 0 && fwrite(mp3_buf, 1, (size_t)bytes, out) != (size_t)bytes) {
            set_err(err, err_len, "Ошибка записи (нет места?)");
            goto done;
        }
    }

    {
        int bytes = lame_encode_flush(lame, mp3_buf, mp3_size);
        if (bytes > 0)
            fwrite(mp3_buf, 1, (size_t)bytes, out);
    }
    result = 0;

done:
    if (lame)
        lame_close(lame);
    if (in)
        fclose(in);
    if (out)
        fclose(out);
    free(pcm);
    free(mp3_buf);
    return result;
}
