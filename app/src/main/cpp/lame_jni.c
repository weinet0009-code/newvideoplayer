// JNI bridge to LAME for "轉成 MP3".
#include <jni.h>
#include <stdint.h>
#include "lame/lame.h"

#define FN(name) Java_com_weinet_newvideoplayer_tools_Mp3Encoder_##name

JNIEXPORT jlong JNICALL FN(nativeInit)(JNIEnv *env, jclass cls, jint sampleRate, jint channels, jint kbps) {
    lame_t l = lame_init();
    if (!l) return 0;
    lame_set_in_samplerate(l, sampleRate);
    lame_set_num_channels(l, channels);
    lame_set_mode(l, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(l, kbps);
    lame_set_quality(l, 3);
    lame_set_write_id3tag_automatic(l, 0);
    if (lame_init_params(l) < 0) { lame_close(l); return 0; }
    return (jlong) (intptr_t) l;
}

JNIEXPORT jint JNICALL FN(nativeEncode)(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint frames, jint channels, jbyteArray out) {
    lame_t l = (lame_t) (intptr_t) h;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize cap = (*env)->GetArrayLength(env, out);
    int n = channels == 1
        ? lame_encode_buffer(l, in, in, frames, (unsigned char *) o, cap)
        : lame_encode_buffer_interleaved(l, in, frames, (unsigned char *) o, cap);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}

JNIEXPORT jint JNICALL FN(nativeFlush)(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    lame_t l = (lame_t) (intptr_t) h;
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int n = lame_encode_flush(l, (unsigned char *) o, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *env, jclass cls, jlong h) {
    lame_close((lame_t) (intptr_t) h);
}
