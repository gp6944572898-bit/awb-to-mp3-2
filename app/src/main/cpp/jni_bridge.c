#include <jni.h>
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
    char err[256] = {0};
    int r = awb_convert(p, subsong, o, bitrate, err, (int)sizeof(err));
    (*env)->ReleaseStringUTFChars(env, path, p);
    (*env)->ReleaseStringUTFChars(env, out, o);
    if (r == 0)
        return NULL;
    return (*env)->NewStringUTF(env, err[0] ? err : "Неизвестная ошибка");
}
