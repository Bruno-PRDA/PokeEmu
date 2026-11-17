#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdint>
#include <vector>

// Plays the core's stereo int16 audio through AAudio.
// Resamples from the core rate to the device rate and nudges the ratio
// (+-0.5%) to keep the buffer half full, so emulation pacing by the clock
// never under/overruns audio.
class AudioOutput {
public:
    ~AudioOutput();

    bool start(double inputRate);
    void stop();
    void pause();
    void resume();
    // Called from the emulation thread.
    void push(const int16_t* frames, size_t count);
    // Drops buffered audio (e.g. after loading a state).
    void clear();
    // Output volume, 0 (silent) to 1 (as the game made it). Takes effect immediately.
    void setGain(float gain) { gain_.store(gain < 0.f ? 0.f : (gain > 1.f ? 1.f : gain)); }

private:
    bool openStream();
    void closeStream();
    size_t available() const;
    void writeFrame(int16_t l, int16_t r);

    static aaudio_data_callback_result_t dataCallback(AAudioStream*, void* user, void* audioData, int32_t numFrames);
    static void errorCallback(AAudioStream*, void* user, aaudio_result_t error);

    AAudioStream* stream_ = nullptr;
    double inputRate_ = 32768.0;
    double outputRate_ = 48000.0;
    bool paused_ = false;
    std::atomic<bool> needsRestart_{false};
    std::atomic<float> gain_{1.0f};

    // Single-producer (emu thread) / single-consumer (AAudio thread) ring of stereo frames.
    std::vector<int16_t> ring_;
    size_t capacityFrames_ = 0;
    std::atomic<size_t> readPos_{0};
    std::atomic<size_t> writePos_{0};

    // Resampler state.
    double phase_ = 0.0;
    int16_t prevL_ = 0, prevR_ = 0;
};
