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

/* ---------- потоковый кодировщик PCM → MP3 ---------- */

struct awb_enc {
    lame_t lame;
    FILE* out;
    int channels;
    short* pcm;              /* выровненная копия входных данных */
    size_t pcm_cap;          /* ёмкость pcm в сэмплах */
    unsigned char carry[4];  /* неполный кадр, перенесённый на следующий вызов */
    int carry_len;
    unsigned char* mp3;
    size_t mp3_cap;
};

awb_enc_t* awb_enc_open(const char* out_path, int sample_rate, int channels, int bitrate_kbps) {
    if (channels < 1 || channels > 2 || sample_rate <= 0)
        return NULL;

    awb_enc_t* e = calloc(1, sizeof(*e));
    if (!e)
        return NULL;
    e->channels = channels;
    e->out = fopen(out_path, "wb");
    e->lame = lame_init();
    if (!e->out || !e->lame)
        goto fail;

    lame_set_in_samplerate(e->lame, sample_rate);
    lame_set_num_channels(e->lame, channels);
    lame_set_mode(e->lame, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(e->lame, bitrate_kbps > 0 ? bitrate_kbps : 192);
    lame_set_quality(e->lame, 5);
    lame_set_bWriteVbrTag(e->lame, 0);
    if (lame_init_params(e->lame) < 0)
        goto fail;
    return e;

fail:
    if (e->lame)
        lame_close(e->lame);
    if (e->out)
        fclose(e->out);
    free(e);
    return NULL;
}

int awb_enc_write(awb_enc_t* e, const unsigned char* data, int bytes) {
    if (!e || bytes < 0)
        return -1;
    const int frame_bytes = 2 * e->channels;
    size_t total = (size_t)e->carry_len + (size_t)bytes;
    int frames = (int)(total / (size_t)frame_bytes);
    if (frames == 0) {
        memcpy(e->carry + e->carry_len, data, (size_t)bytes);
        e->carry_len += bytes;
        return 0;
    }

    size_t need_pcm = (size_t)frames * (size_t)e->channels;
    if (need_pcm > e->pcm_cap) {
        short* p = realloc(e->pcm, need_pcm * sizeof(short));
        if (!p)
            return -1;
        e->pcm = p;
        e->pcm_cap = need_pcm;
    }

    /* сначала хвост прошлого вызова, затем новые данные */
    size_t used_bytes = (size_t)frames * (size_t)frame_bytes;
    unsigned char* dst = (unsigned char*)e->pcm;
    memcpy(dst, e->carry, (size_t)e->carry_len);
    size_t from_new = used_bytes - (size_t)e->carry_len;
    memcpy(dst + e->carry_len, data, from_new);

    e->carry_len = (int)(total - used_bytes);
    memcpy(e->carry, data + from_new, (size_t)e->carry_len);

    size_t need_mp3 = (size_t)(1.25 * frames + 7200);
    if (need_mp3 > e->mp3_cap) {
        unsigned char* p = realloc(e->mp3, need_mp3);
        if (!p)
            return -1;
        e->mp3 = p;
        e->mp3_cap = need_mp3;
    }

    int n;
    if (e->channels == 1)
        n = lame_encode_buffer(e->lame, e->pcm, e->pcm, frames, e->mp3, (int)e->mp3_cap);
    else
        n = lame_encode_buffer_interleaved(e->lame, e->pcm, frames, e->mp3, (int)e->mp3_cap);
    if (n < 0)
        return -1;
    if (n > 0 && fwrite(e->mp3, 1, (size_t)n, e->out) != (size_t)n)
        return -1;
    return 0;
}

int awb_enc_close(awb_enc_t* e) {
    if (!e)
        return -1;
    int result = 0;

    unsigned char tail[8192];
    int n = lame_encode_flush(e->lame, tail, (int)sizeof(tail));
    if (n > 0 && fwrite(tail, 1, (size_t)n, e->out) != (size_t)n)
        result = -1;
    if (fclose(e->out) != 0)
        result = -1;
    lame_close(e->lame);
    free(e->pcm);
    free(e->mp3);
    free(e);
    return result;
}
