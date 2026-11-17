#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "audio_output.h"
#include "libretro_core.h"
#include "video_output.h"

// One running game: core + emulation thread + audio/video + battery save persistence.
class Session {
public:
    static Session* create(const std::string& coreLibrary, const std::string& systemDir, const std::string& saveDir);
    ~Session();

    // Memory-maps the ROM file (large DS ROMs never pass through the Java heap) and
    // loads it; if savePath exists its contents are copied into the core's save RAM.
    bool loadGame(const std::string& romPath, const std::string& savePath);
    void start();
    void pause();
    void resume();
    void stop();  // stops the thread and flushes save RAM

    void setWindow(ANativeWindow* window) { video_.setWindow(window); }
    void setButtons(uint32_t mask) { buttons_.store(mask); }
    // Touchscreen: libretro pointer coordinates (-0x7FFF..0x7FFF over the whole frame).
    void setPointer(int x, int y, bool pressed) {
        pointerX_.store(x);
        pointerY_.store(y);
        pointerPressed_.store(pressed);
    }
    void setSpeed(int multiplier) { speed_.store(multiplier < 1 ? 1 : multiplier); }
    void reset();
    bool setCheats(const std::vector<std::string>& codes);

    std::vector<uint8_t> saveState();
    bool loadState(const std::vector<uint8_t>& state);
    bool snapshot(std::vector<uint32_t>& argb, unsigned& w, unsigned& h) { return video_.snapshotArgb(argb, w, h); }
    // Writes save RAM to disk if it changed since the last flush. Returns false on I/O error.
    bool flushSaveRam(bool force = false);
    std::vector<uint8_t> readMemory(unsigned region, size_t offset, size_t length);
    // Reads console memory by bus address (e.g. 0x02000000 = GBA EWRAM).
    std::vector<uint8_t> readBus(uint32_t address, size_t length);
    double fps() const { return core_.avInfo().timing.fps; }

private:
    Session() = default;
    void loop();

    static Session* current_;
    static void onVideo(const void* data, unsigned w, unsigned h, size_t pitch, retro_pixel_format fmt);
    static size_t onAudio(const int16_t* data, size_t frames);
    static int16_t onInput(unsigned port, unsigned device, unsigned index, unsigned id);

    LibretroCore core_;
    AudioOutput audio_;
    VideoOutput video_;
    std::mutex coreMutex_;  // guards every call into the core

    std::thread thread_;
    std::mutex stateMutex_;
    std::condition_variable stateCv_;
    bool running_ = false;
    bool paused_ = false;

    std::atomic<uint32_t> buttons_{0};
    std::atomic<int> pointerX_{0}, pointerY_{0};
    std::atomic<bool> pointerPressed_{false};
    std::atomic<int> speed_{1};
    bool audioMuted_ = false;  // true while fast-forwarding

    void* romMap_ = nullptr;  // mmap of the ROM file, kept until the core is closed
    size_t romMapSize_ = 0;
    std::mutex saveMutex_;  // guards savePath_/lastSavedRam_ and the save file
    std::string savePath_;
    std::vector<uint8_t> lastSavedRam_;
};
