#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "libretro.h"

// Loads a libretro core shared library and exposes its API.
// libretro cores are process-global (static callbacks), so only one
// LibretroCore may be loaded at a time.
class LibretroCore {
public:
    struct Callbacks {
        void (*video)(const void* data, unsigned width, unsigned height, size_t pitch, retro_pixel_format fmt) = nullptr;
        size_t (*audioBatch)(const int16_t* data, size_t frames) = nullptr;
        int16_t (*inputState)(unsigned port, unsigned device, unsigned index, unsigned id) = nullptr;
    };

    LibretroCore() = default;
    ~LibretroCore();
    LibretroCore(const LibretroCore&) = delete;
    LibretroCore& operator=(const LibretroCore&) = delete;

    // dlopen()s the core and calls retro_init().
    bool open(const std::string& libraryName, const std::string& systemDir, const std::string& saveDir,
              const Callbacks& callbacks);
    // Loads ROM bytes (the core must not need a full path).
    bool loadGame(const std::vector<uint8_t>& rom);
    void runFrame();
    void reset();
    // Unloads the game and core.
    void close();

    bool isGameLoaded() const { return gameLoaded_; }
    const retro_system_av_info& avInfo() const { return avInfo_; }
    const std::string& coreName() const { return coreName_; }

    std::vector<uint8_t> serialize();
    bool unserialize(const std::vector<uint8_t>& state);

    // Region is a RETRO_MEMORY_* id. Returns nullptr when unavailable.
    uint8_t* memoryData(unsigned region, size_t* size);
    // Host pointer for a console bus address (from the core's memory map), with
    // the bytes available from there in *available. nullptr if unmapped.
    uint8_t* busAddress(uint32_t address, size_t* available);

private:
    template <typename T> bool sym(T& fn, const char* name);
    static bool environment(unsigned cmd, void* data);
    static void videoRefresh(const void* data, unsigned width, unsigned height, size_t pitch);
    static void audioSample(int16_t left, int16_t right);
    static size_t audioSampleBatch(const int16_t* data, size_t frames);
    static void inputPoll();
    static int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id);
    static void log(retro_log_level level, const char* fmt, ...);

    void* handle_ = nullptr;
    bool initialized_ = false;
    bool gameLoaded_ = false;
    retro_system_av_info avInfo_{};
    std::string coreName_;
    std::string systemDir_;
    std::string saveDir_;
    Callbacks callbacks_;
    retro_pixel_format pixelFormat_ = RETRO_PIXEL_FORMAT_0RGB1555;

    struct MemoryRegion {
        uint8_t* ptr;
        size_t offset;
        uint32_t start;
        size_t len;
    };
    std::vector<MemoryRegion> memoryMap_;

    void (*retro_init_)() = nullptr;
    void (*retro_deinit_)() = nullptr;
    void (*retro_set_environment_)(retro_environment_t) = nullptr;
    void (*retro_set_video_refresh_)(retro_video_refresh_t) = nullptr;
    void (*retro_set_audio_sample_)(retro_audio_sample_t) = nullptr;
    void (*retro_set_audio_sample_batch_)(retro_audio_sample_batch_t) = nullptr;
    void (*retro_set_input_poll_)(retro_input_poll_t) = nullptr;
    void (*retro_set_input_state_)(retro_input_state_t) = nullptr;
    void (*retro_get_system_info_)(retro_system_info*) = nullptr;
    void (*retro_get_system_av_info_)(retro_system_av_info*) = nullptr;
    void (*retro_set_controller_port_device_)(unsigned, unsigned) = nullptr;
    bool (*retro_load_game_)(const retro_game_info*) = nullptr;
    void (*retro_unload_game_)() = nullptr;
    void (*retro_run_)() = nullptr;
    void (*retro_reset_)() = nullptr;
    size_t (*retro_serialize_size_)() = nullptr;
    bool (*retro_serialize_)(void*, size_t) = nullptr;
    bool (*retro_unserialize_)(const void*, size_t) = nullptr;
    void* (*retro_get_memory_data_)(unsigned) = nullptr;
    size_t (*retro_get_memory_size_)(unsigned) = nullptr;

    static LibretroCore* active_;
};
