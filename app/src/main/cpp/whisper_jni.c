#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml-backend.h"

static atomic_int g_abort = 0;

static atomic_int g_progress = 0;
static atomic_int g_backends_loaded = 0;

static bool abort_cb(void *user_data) {
    (void) user_data;
    return atomic_load(&g_abort) != 0;
}

static void progress_cb(struct whisper_context *ctx, struct whisper_state *state, int progress, void *user_data) {
    (void) ctx; (void) state; (void) user_data;
    atomic_store(&g_progress, progress);
}

/* Loads the best libggml-cpu-*.so for this CPU from the app's native library folder (arm64 only). */
JNIEXPORT void JNICALL
Java_com_weinet_newvideoplayer_tools_LocalWhisper_nativeLoadBackends(JNIEnv *env, jclass clazz, jstring dir) {
#ifdef GGML_BACKEND_DL
    if (atomic_exchange(&g_backends_loaded, 1)) return;
    const char *d = (*env)->GetStringUTFChars(env, dir, NULL);
    ggml_backend_load_all_from_path(d);
    (*env)->ReleaseStringUTFChars(env, dir, d);
#else
    (void) env; (void) dir;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_weinet_newvideoplayer_tools_LocalWhisper_nativeSystemInfo(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}

/* Percent (0..100) of the chunk currently being transcribed. */
JNIEXPORT jint JNICALL
Java_com_weinet_newvideoplayer_tools_LocalWhisper_nativeProgress(JNIEnv *env, jclass clazz) {
    return atomic_load(&g_progress);
}

JNIEXPORT jlong JNICALL
Java_com_weinet_newvideoplayer_tools_LocalWhisper_nativeInit(JNIEnv *env, jclass clazz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_weinet_newvideoplayer_tools_LocalWhisper_nativeFree(JNIEnv *env, jclass clazz, jlong ptr) {
    if (ptr) whisper_free((struct whisper_context *) (intptr_t) ptr);
}

JNIEXPORT void JNICALL
Java_com_weinet_newvideoplayer_tools_LocalWhisper_nativeAbort(JNIEnv *env, jclass clazz, jboolean flag) {
    atomic_store(&g_abort, flag ? 1 : 0);
}

/*
 * Transcribes 16 kHz mono float PCM. Returns UTF-8 bytes of records
 * "t0 \x1f t1 \x1f text \x1e" (times in 10 ms units), or null on failure/abort.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_weinet_newvideoplayer_tools_LocalWhisper_nativeTranscribe(
        JNIEnv *env, jclass clazz, jlong ptr, jfloatArray pcm, jstring lang, jstring prompt, jint threads, jstring vadModel) {
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (!ctx) return NULL;

    jsize n = (*env)->GetArrayLength(env, pcm);
    jfloat *data = (*env)->GetFloatArrayElements(env, pcm, NULL);
    const char *l = lang ? (*env)->GetStringUTFChars(env, lang, NULL) : NULL;
    const char *pr = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;
    const char *vm = vadModel ? (*env)->GetStringUTFChars(env, vadModel, NULL) : NULL;

    struct whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.no_context = true;
    wp.n_threads = threads;
    wp.language = l ? l : "auto";
    wp.detect_language = false;
    wp.initial_prompt = pr;
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = NULL;
    wp.progress_callback = progress_cb;
    wp.progress_callback_user_data = NULL;
    /* Silero VAD: only speech is sent to Whisper (faster, fewer made-up lines in silence).
     * Segment times are mapped back to the original timeline by whisper.cpp. */
    wp.vad = vm != NULL;
    wp.vad_model_path = vm;
    atomic_store(&g_progress, 0);

    int rc = whisper_full(ctx, wp, data, n);

    (*env)->ReleaseFloatArrayElements(env, pcm, data, JNI_ABORT);
    if (l) (*env)->ReleaseStringUTFChars(env, lang, l);
    if (pr) (*env)->ReleaseStringUTFChars(env, prompt, pr);
    if (vm) (*env)->ReleaseStringUTFChars(env, vadModel, vm);
    if (rc != 0 || atomic_load(&g_abort)) return NULL;

    size_t cap = 4096, len = 0;
    char *buf = malloc(cap);
    if (!buf) return NULL;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        char head[64];
        int hl = snprintf(head, sizeof head, "%lld\x1f%lld\x1f",
                          (long long) whisper_full_get_segment_t0(ctx, i),
                          (long long) whisper_full_get_segment_t1(ctx, i));
        const char *text = whisper_full_get_segment_text(ctx, i);
        size_t tl = text ? strlen(text) : 0;
        size_t need = len + (size_t) hl + tl + 2;
        if (need > cap) {
            while (need > cap) cap *= 2;
            char *nb = realloc(buf, cap);
            if (!nb) { free(buf); return NULL; }
            buf = nb;
        }
        memcpy(buf + len, head, (size_t) hl); len += (size_t) hl;
        if (tl) { memcpy(buf + len, text, tl); len += tl; }
        buf[len++] = '\x1e';
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) len);
    if (out && len) (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) buf);
    free(buf);
    return out;
}
