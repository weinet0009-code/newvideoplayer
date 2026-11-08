// Software video decoding with FFmpeg (libavcodec, dav1d for AV1) for FfmpegVideoDecoder.kt.
// Frames are kept natively and drawn straight into the player's Surface as YV12.
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <cstring>
#include <deque>
#include <mutex>
#include <unordered_map>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

#define TAG "nvp_ffvideo"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define FN(name) Java_com_weinet_newvideoplayer_ffmpeg_FfmpegVideoDecoder_##name

namespace {

constexpr int kYV12 = 0x32315659;
constexpr size_t kMaxPending = 64;

struct Ctx {
  AVCodecContext *codec = nullptr;
  AVPacket *pkt = nullptr;
  std::mutex mu;
  std::deque<AVFrame *> pending;                 // decoded, not handed to Java yet
  std::unordered_map<int, AVFrame *> handedOut;  // id -> frame owned by an output buffer
  int nextId = 1;
  // Rendering state (playback thread).
  ANativeWindow *window = nullptr;
  jobject surface = nullptr;  // global ref
  int winW = 0, winH = 0;
  SwsContext *sws = nullptr;
  AVFrame *conv = nullptr;
};

void freePending(Ctx *c) {
  for (AVFrame *f : c->pending) av_frame_free(&f);
  c->pending.clear();
}

// Pulls every frame the decoder has ready into the pending queue.
int drain(Ctx *c) {
  while (true) {
    AVFrame *f = av_frame_alloc();
    int r = avcodec_receive_frame(c->codec, f);
    if (r < 0) {
      av_frame_free(&f);
      return (r == AVERROR(EAGAIN) || r == AVERROR_EOF) ? 0 : r;
    }
    std::lock_guard<std::mutex> lock(c->mu);
    if (c->pending.size() >= kMaxPending) {
      AVFrame *old = c->pending.front();
      c->pending.pop_front();
      av_frame_free(&old);
    }
    c->pending.push_back(f);
  }
}

void copyPlane(uint8_t *dst, int dstStride, const uint8_t *src, int srcStride, int width, int rows) {
  for (int y = 0; y < rows; y++) {
    memcpy(dst, src, width);
    dst += dstStride;
    src += srcStride;
  }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL FN(nativeInit)(JNIEnv *env, jclass, jstring jname, jbyteArray extra,
                                        jint threads) {
  const char *name = env->GetStringUTFChars(jname, nullptr);
  const AVCodec *codec = avcodec_find_decoder_by_name(name);
  env->ReleaseStringUTFChars(jname, name);
  if (!codec) return 0;
  auto *c = new Ctx();
  c->codec = avcodec_alloc_context3(codec);
  c->pkt = av_packet_alloc();
  if (!c->codec || !c->pkt) { delete c; return 0; }
  c->codec->pkt_timebase = AVRational{1, 1000000};
  c->codec->thread_count = threads;
  c->codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  if (extra) {
    jsize n = env->GetArrayLength(extra);
    if (n > 0) {
      c->codec->extradata = static_cast<uint8_t *>(av_mallocz(n + AV_INPUT_BUFFER_PADDING_SIZE));
      env->GetByteArrayRegion(extra, 0, n, reinterpret_cast<jbyte *>(c->codec->extradata));
      c->codec->extradata_size = n;
    }
  }
  if (avcodec_open2(c->codec, codec, nullptr) < 0) {
    avcodec_free_context(&c->codec);
    av_packet_free(&c->pkt);
    delete c;
    return 0;
  }
  return reinterpret_cast<jlong>(c);
}

// Returns the number of decoded frames waiting, or a negative AVERROR on a fatal error.
// Broken packets are skipped.
JNIEXPORT jint JNICALL FN(nativeDecode)(JNIEnv *env, jclass, jlong h, jobject data, jint size,
                                         jlong timeUs) {
  auto *c = reinterpret_cast<Ctx *>(h);
  c->pkt->data = static_cast<uint8_t *>(env->GetDirectBufferAddress(data));
  c->pkt->size = size;
  c->pkt->pts = timeUs;
  c->pkt->dts = AV_NOPTS_VALUE;
  int r = avcodec_send_packet(c->codec, c->pkt);
  while (r == AVERROR(EAGAIN)) {
    int d = drain(c);
    if (d < 0) break;
    r = avcodec_send_packet(c->codec, c->pkt);
  }
  c->pkt->data = nullptr;
  c->pkt->size = 0;
  if (r < 0 && r != AVERROR_INVALIDDATA && r != AVERROR(EAGAIN)) {
    char buf[128];
    av_strerror(r, buf, sizeof(buf));
    LOGE("send_packet: %s", buf);
    return r;
  }
  int d = drain(c);
  if (d < 0 && d != AVERROR_INVALIDDATA) return d;
  std::lock_guard<std::mutex> lock(c->mu);
  return static_cast<jint>(c->pending.size());
}

// End of stream: makes FFmpeg return every frame it still holds. Returns the pending count.
JNIEXPORT jint JNICALL FN(nativeSendEos)(JNIEnv *, jclass, jlong h) {
  auto *c = reinterpret_cast<Ctx *>(h);
  int r = avcodec_send_packet(c->codec, nullptr);
  if (r < 0 && r != AVERROR_EOF) return r;
  int d = drain(c);
  if (d < 0 && d != AVERROR_INVALIDDATA) return d;
  std::lock_guard<std::mutex> lock(c->mu);
  return static_cast<jint>(c->pending.size());
}

JNIEXPORT void JNICALL FN(nativeFlush)(JNIEnv *, jclass, jlong h) {
  auto *c = reinterpret_cast<Ctx *>(h);
  avcodec_flush_buffers(c->codec);
  std::lock_guard<std::mutex> lock(c->mu);
  freePending(c);
}

// Hands the oldest decoded frame to Java. info = {ptsUs, width, height}. Returns its id, or 0.
JNIEXPORT jint JNICALL FN(nativeDequeue)(JNIEnv *env, jclass, jlong h, jlongArray info) {
  auto *c = reinterpret_cast<Ctx *>(h);
  std::lock_guard<std::mutex> lock(c->mu);
  if (c->pending.empty()) return 0;
  AVFrame *f = c->pending.front();
  c->pending.pop_front();
  int id = c->nextId++;
  if (c->nextId <= 0) c->nextId = 1;
  c->handedOut[id] = f;
  jlong pts = f->best_effort_timestamp != AV_NOPTS_VALUE ? f->best_effort_timestamp : f->pts;
  jlong v[3] = {pts, f->width, f->height};
  env->SetLongArrayRegion(info, 0, 3, v);
  return id;
}

JNIEXPORT void JNICALL FN(nativeReleaseFrame)(JNIEnv *, jclass, jlong h, jint id) {
  auto *c = reinterpret_cast<Ctx *>(h);
  std::lock_guard<std::mutex> lock(c->mu);
  auto it = c->handedOut.find(id);
  if (it == c->handedOut.end()) return;
  av_frame_free(&it->second);
  c->handedOut.erase(it);
}

// Draws frame [id] into [surface]. Returns 0 on success, -1 on failure.
JNIEXPORT jint JNICALL FN(nativeRender)(JNIEnv *env, jclass, jlong h, jobject surface, jint id) {
  auto *c = reinterpret_cast<Ctx *>(h);
  std::lock_guard<std::mutex> lock(c->mu);
  auto it = c->handedOut.find(id);
  if (it == c->handedOut.end()) return -1;
  AVFrame *f = it->second;

  if (!c->surface || !env->IsSameObject(c->surface, surface)) {
    if (c->window) ANativeWindow_release(c->window);
    if (c->surface) env->DeleteGlobalRef(c->surface);
    c->window = ANativeWindow_fromSurface(env, surface);
    c->surface = env->NewGlobalRef(surface);
    c->winW = c->winH = 0;
  }
  if (!c->window) return -1;
  const int w = f->width, h2 = f->height;
  if (w != c->winW || h2 != c->winH) {
    if (ANativeWindow_setBuffersGeometry(c->window, w, h2, kYV12) != 0) return -1;
    c->winW = w;
    c->winH = h2;
  }

  const AVFrame *src = f;
  if (f->format != AV_PIX_FMT_YUV420P && f->format != AV_PIX_FMT_YUVJ420P) {
    // 10-bit, 4:2:2, NV12 … → 8-bit 4:2:0.
    c->sws = sws_getCachedContext(c->sws, w, h2, static_cast<AVPixelFormat>(f->format), w, h2,
                                  AV_PIX_FMT_YUV420P, SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
    if (!c->sws) return -1;
    if (!c->conv || c->conv->width != w || c->conv->height != h2) {
      av_frame_free(&c->conv);
      c->conv = av_frame_alloc();
      c->conv->format = AV_PIX_FMT_YUV420P;
      c->conv->width = w;
      c->conv->height = h2;
      if (av_frame_get_buffer(c->conv, 32) < 0) { av_frame_free(&c->conv); return -1; }
    }
    sws_scale(c->sws, f->data, f->linesize, 0, h2, c->conv->data, c->conv->linesize);
    src = c->conv;
  }

  ANativeWindow_Buffer buf;
  if (ANativeWindow_lock(c->window, &buf, nullptr) != 0) return -1;
  auto *bits = static_cast<uint8_t *>(buf.bits);
  const int rows = h2 < buf.height ? h2 : buf.height;
  const int cols = w < buf.stride ? w : buf.stride;
  copyPlane(bits, buf.stride, src->data[0], src->linesize[0], cols, rows);
  const int uvStride = ((buf.stride / 2) + 15) & ~15;
  const int uvRows = (buf.height + 1) / 2;
  uint8_t *vDst = bits + buf.stride * buf.height;
  uint8_t *uDst = vDst + uvStride * uvRows;
  const int uvCols = (cols + 1) / 2;
  const int uvCopyRows = (rows + 1) / 2;
  copyPlane(vDst, uvStride, src->data[2], src->linesize[2], uvCols, uvCopyRows);
  copyPlane(uDst, uvStride, src->data[1], src->linesize[1], uvCols, uvCopyRows);
  ANativeWindow_unlockAndPost(c->window);
  return 0;
}

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *env, jclass, jlong h) {
  auto *c = reinterpret_cast<Ctx *>(h);
  if (!c) return;
  {
    std::lock_guard<std::mutex> lock(c->mu);
    freePending(c);
    for (auto &e : c->handedOut) av_frame_free(&e.second);
    c->handedOut.clear();
  }
  if (c->window) ANativeWindow_release(c->window);
  if (c->surface) env->DeleteGlobalRef(c->surface);
  sws_freeContext(c->sws);
  av_frame_free(&c->conv);
  avcodec_free_context(&c->codec);
  av_packet_free(&c->pkt);
  delete c;
}

}  // extern "C"
