#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "converter.h"

JNIEXPORT jint JNICALL
Java_com_awb2mp3_app_NativeConverter_count(JNIEnv* env, jobject thiz, jstring path) {
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    int n = awb_count(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return n;
}

/* Возвращает null при успехе, иначе текст ошибки */
JNIEXPORT jstring JNICALL
Java_com_awb2mp3_app_NativeConverter_convert(JNIEnv* env, jobject thiz, jstring path,
                                             jint subsong, jstring out, jint bitrate) {
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    const char* o = (*env)->GetStringUTFChars(env, out, NULL);
    char err[1400] = {0};
    int r = awb_convert(p, subsong, o, bitrate, err, (int)sizeof(err));
    (*env)->ReleaseStringUTFChars(env, path, p);
    (*env)->ReleaseStringUTFChars(env, out, o);
    if (r == 0)
        return NULL;
    return (*env)->NewStringUTF(env, err[0] ? err : "Неизвестная ошибка");
}

/* Диагностика файла, который не удалось открыть */
JNIEXPORT jstring JNICALL
Java_com_awb2mp3_app_NativeConverter_diagnose(JNIEnv* env, jobject thiz, jstring path) {
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    char buf[1400] = {0};
    awb_diag(p, buf, (int)sizeof(buf));
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (*env)->NewStringUTF(env, buf);
}

/* Потоковый кодировщик: открыть (возвращает 0 при ошибке), записать порцию, закрыть */
JNIEXPORT jlong JNICALL
Java_com_awb2mp3_app_NativeConverter_encoderOpen(JNIEnv* env, jobject thiz, jstring out,
                                                 jint rate, jint channels, jint bitrate) {
    const char* o = (*env)->GetStringUTFChars(env, out, NULL);
    awb_enc_t* e = awb_enc_open(o, rate, channels, bitrate);
    (*env)->ReleaseStringUTFChars(env, out, o);
    return (jlong)(intptr_t)e;
}

JNIEXPORT jint JNICALL
Java_com_awb2mp3_app_NativeConverter_encoderWrite(JNIEnv* env, jobject thiz, jlong handle,
                                                  jbyteArray data, jint len) {
    jbyte* p = (*env)->GetByteArrayElements(env, data, NULL);
    if (!p)
        return -1;
    int r = awb_enc_write((awb_enc_t*)(intptr_t)handle, (const unsigned char*)p, len);
    (*env)->ReleaseByteArrayElements(env, data, p, JNI_ABORT);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_awb2mp3_app_NativeConverter_encoderClose(JNIEnv* env, jobject thiz, jlong handle) {
    return awb_enc_close((awb_enc_t*)(intptr_t)handle);
}

