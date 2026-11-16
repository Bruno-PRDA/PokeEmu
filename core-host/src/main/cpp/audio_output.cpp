#include "audio_output.h"

#include <android/log.h>

#include <algorithm>
#include <cstring>

#define TAG "AudioOutput"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

AudioOutput::~AudioOutput() { stop(); }

bool AudioOutput::start(double inputRate) {
    inputRate_ = inputRate > 0 ? inputRate : 32768.0;
    if (!openStream()) return false;
    // ~200 ms of buffer; we aim to keep it half full.
    capacityFrames_ = static_cast<size_t>(outputRate_ * 0.2);
    ring_.assign(capacityFrames_ * 2, 0);
    clear();
    paused_ = false;
    return AAudioStream_requestStart(stream_) == AAUDIO_OK;
}

bool AudioOutput::openStream() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(builder, &AudioOutput::dataCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, &AudioOutput::errorCallback, this);
    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK) {
        LOGE("openStream failed: %s", AAudio_convertResultToText(r));
        stream_ = nullptr;
        return false;
    }
    outputRate_ = AAudioStream_getSampleRate(stream_);
    return true;
}

void AudioOutput::closeStream() {
    if (stream_) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

void AudioOutput::stop() { closeStream(); }

void AudioOutput::pause() {
    paused_ = true;
    if (stream_) AAudioStream_requestPause(stream_);
}

void AudioOutput::resume() {
    paused_ = false;
    clear();
    if (stream_) AAudioStream_requestStart(stream_);
}

void AudioOutput::clear() {
    readPos_.store(0);
    writePos_.store(0);
    phase_ = 0.0;
}

size_t AudioOutput::available() const {
    size_t w = writePos_.load(std::memory_order_acquire);
    size_t r = readPos_.load(std::memory_order_acquire);
    return (w + capacityFrames_ - r) % capacityFrames_;
}

void AudioOutput::writeFrame(int16_t l, int16_t r) {
    size_t w = writePos_.load(std::memory_order_relaxed);
    size_t next = (w + 1) % capacityFrames_;
    if (next == readPos_.load(std::memory_order_acquire)) return;  // full: drop
    ring_[w * 2] = l;
    ring_[w * 2 + 1] = r;
    writePos_.store(next, std::memory_order_release);
}

void AudioOutput::push(const int16_t* frames, size_t count) {
    if (needsRestart_.exchange(false)) {
        // Output device changed (e.g. headphones unplugged): reopen.
        closeStream();
        if (openStream()) {
            capacityFrames_ = static_cast<size_t>(outputRate_ * 0.2);
            ring_.assign(capacityFrames_ * 2, 0);
            clear();
            if (!paused_) AAudioStream_requestStart(stream_);
        }
    }
    if (!stream_ || capacityFrames_ == 0) return;

    // Dynamic rate control: fill above target -> produce fewer output frames.
    double fill = static_cast<double>(available()) / capacityFrames_;
    double adjust = 1.0 - (fill - 0.5) * 0.01;  // +-0.5%
    double step = (inputRate_ / outputRate_) / adjust;  // input frames advanced per output frame

    for (size_t i = 0; i < count; ++i) {
        int16_t l = frames[i * 2], r = frames[i * 2 + 1];
        // Emit output frames that fall between prev and current input sample.
        while (phase_ < 1.0) {
            int16_t ol = static_cast<int16_t>(prevL_ + (l - prevL_) * phase_);
            int16_t orr = static_cast<int16_t>(prevR_ + (r - prevR_) * phase_);
            writeFrame(ol, orr);
            phase_ += step;
        }
        phase_ -= 1.0;
        prevL_ = l;
        prevR_ = r;
    }
}

aaudio_data_callback_result_t AudioOutput::dataCallback(AAudioStream*, void* user, void* audioData, int32_t numFrames) {
    auto* self = static_cast<AudioOutput*>(user);
    auto* out = static_cast<int16_t*>(audioData);
    size_t r = self->readPos_.load(std::memory_order_relaxed);
    size_t w = self->writePos_.load(std::memory_order_acquire);
    int32_t i = 0;
    for (; i < numFrames && r != w; ++i) {
        out[i * 2] = self->ring_[r * 2];
        out[i * 2 + 1] = self->ring_[r * 2 + 1];
        r = (r + 1) % self->capacityFrames_;
    }
    self->readPos_.store(r, std::memory_order_release);
    if (i < numFrames) std::memset(out + i * 2, 0, static_cast<size_t>(numFrames - i) * 2 * sizeof(int16_t));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioOutput::errorCallback(AAudioStream*, void* user, aaudio_result_t error) {
    if (error == AAUDIO_ERROR_DISCONNECTED) static_cast<AudioOutput*>(user)->needsRestart_.store(true);
}
