#include "libretro_core.h"

#include <android/log.h>
#include <dlfcn.h>

#include <cstdarg>
#include <cstdio>

#define TAG "LibretroCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

LibretroCore* LibretroCore::active_ = nullptr;

LibretroCore::~LibretroCore() { close(); }

template <typename T> bool LibretroCore::sym(T& fn, const char* name) {
    fn = reinterpret_cast<T>(dlsym(handle_, name));
    if (!fn) LOGE("core is missing symbol %s", name);
    return fn != nullptr;
}

bool LibretroCore::open(const std::string& libraryName, const std::string& systemDir, const std::string& saveDir,
                        const Callbacks& callbacks) {
    if (active_) {
        LOGE("a core is already open");
        return false;
    }
    handle_ = dlopen(libraryName.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle_) {
        LOGE("dlopen(%s) failed: %s", libraryName.c_str(), dlerror());
        return false;
    }
    bool ok = sym(retro_init_, "retro_init") && sym(retro_deinit_, "retro_deinit") &&
              sym(retro_set_environment_, "retro_set_environment") &&
              sym(retro_set_video_refresh_, "retro_set_video_refresh") &&
              sym(retro_set_audio_sample_, "retro_set_audio_sample") &&
              sym(retro_set_audio_sample_batch_, "retro_set_audio_sample_batch") &&
              sym(retro_set_input_poll_, "retro_set_input_poll") &&
              sym(retro_set_input_state_, "retro_set_input_state") &&
              sym(retro_get_system_info_, "retro_get_system_info") &&
              sym(retro_get_system_av_info_, "retro_get_system_av_info") &&
              sym(retro_set_controller_port_device_, "retro_set_controller_port_device") &&
              sym(retro_load_game_, "retro_load_game") && sym(retro_unload_game_, "retro_unload_game") &&
              sym(retro_run_, "retro_run") && sym(retro_reset_, "retro_reset") &&
              sym(retro_serialize_size_, "retro_serialize_size") && sym(retro_serialize_, "retro_serialize") &&
              sym(retro_unserialize_, "retro_unserialize") &&
              sym(retro_get_memory_data_, "retro_get_memory_data") &&
              sym(retro_get_memory_size_, "retro_get_memory_size");
    if (!ok) {
        dlclose(handle_);
        handle_ = nullptr;
        return false;
    }

    active_ = this;
    systemDir_ = systemDir;
    saveDir_ = saveDir;
    callbacks_ = callbacks;

    retro_system_info info{};
    retro_get_system_info_(&info);
    coreName_ = std::string(info.library_name ? info.library_name : "?") + " " +
                (info.library_version ? info.library_version : "");
    if (info.need_fullpath) {
        LOGE("core %s needs a full path; not supported", coreName_.c_str());
        close();
        return false;
    }

    retro_set_environment_(&LibretroCore::environment);
    retro_set_video_refresh_(&LibretroCore::videoRefresh);
    retro_set_audio_sample_(&LibretroCore::audioSample);
    retro_set_audio_sample_batch_(&LibretroCore::audioSampleBatch);
    retro_set_input_poll_(&LibretroCore::inputPoll);
    retro_set_input_state_(&LibretroCore::inputState);
    retro_init_();
    initialized_ = true;
    LOGI("opened core %s", coreName_.c_str());
    return true;
}

bool LibretroCore::loadGame(const std::vector<uint8_t>& rom) {
    if (!initialized_ || gameLoaded_) return false;
    retro_game_info game{};
    game.path = nullptr;
    game.data = rom.data();
    game.size = rom.size();
    if (!retro_load_game_(&game)) {
        LOGE("retro_load_game failed");
        return false;
    }
    gameLoaded_ = true;
    retro_get_system_av_info_(&avInfo_);
    retro_set_controller_port_device_(0, RETRO_DEVICE_JOYPAD);
    LOGI("game loaded: %ux%u @ %.4f fps, %.0f Hz audio", avInfo_.geometry.base_width, avInfo_.geometry.base_height,
         avInfo_.timing.fps, avInfo_.timing.sample_rate);
    return true;
}

void LibretroCore::runFrame() {
    if (gameLoaded_) retro_run_();
}

void LibretroCore::reset() {
    if (gameLoaded_) retro_reset_();
}

void LibretroCore::close() {
    if (gameLoaded_) {
        retro_unload_game_();
        gameLoaded_ = false;
    }
    if (initialized_) {
        retro_deinit_();
        initialized_ = false;
    }
    if (handle_) {
        dlclose(handle_);
        handle_ = nullptr;
    }
    if (active_ == this) active_ = nullptr;
}

std::vector<uint8_t> LibretroCore::serialize() {
    std::vector<uint8_t> out;
    if (!gameLoaded_) return out;
    size_t size = retro_serialize_size_();
    if (size == 0) return out;
    out.resize(size);
    if (!retro_serialize_(out.data(), size)) out.clear();
    return out;
}

bool LibretroCore::unserialize(const std::vector<uint8_t>& state) {
    if (!gameLoaded_ || state.empty()) return false;
    return retro_unserialize_(state.data(), state.size());
}

uint8_t* LibretroCore::memoryData(unsigned region, size_t* size) {
    if (!gameLoaded_) {
        *size = 0;
        return nullptr;
    }
    *size = retro_get_memory_size_(region);
    return static_cast<uint8_t*>(retro_get_memory_data_(region));
}

// ---- static callbacks -------------------------------------------------------

bool LibretroCore::environment(unsigned cmd, void* data) {
    LibretroCore* self = active_;
    if (!self) return false;
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto fmt = *static_cast<const retro_pixel_format*>(data);
            if (fmt != RETRO_PIXEL_FORMAT_RGB565 && fmt != RETRO_PIXEL_FORMAT_XRGB8888 &&
                fmt != RETRO_PIXEL_FORMAT_0RGB1555) {
                return false;
            }
            self->pixelFormat_ = fmt;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char**>(data) = self->systemDir_.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = self->saveDir_.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = &LibretroCore::log;
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            return true;
        default:
            // Unknown/unsupported commands: core falls back to its defaults
            // (e.g. GET_VARIABLE -> default core options).
            return false;
    }
}

void LibretroCore::videoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    LibretroCore* self = active_;
    if (self && self->callbacks_.video) self->callbacks_.video(data, width, height, pitch, self->pixelFormat_);
}

void LibretroCore::audioSample(int16_t left, int16_t right) {
    int16_t frame[2] = {left, right};
    audioSampleBatch(frame, 1);
}

size_t LibretroCore::audioSampleBatch(const int16_t* data, size_t frames) {
    LibretroCore* self = active_;
    if (self && self->callbacks_.audioBatch) return self->callbacks_.audioBatch(data, frames);
    return frames;
}

void LibretroCore::inputPoll() {}

int16_t LibretroCore::inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    LibretroCore* self = active_;
    if (self && self->callbacks_.inputState) return self->callbacks_.inputState(port, device, index, id);
    return 0;
}

void LibretroCore::log(retro_log_level level, const char* fmt, ...) {
    // Cores log a lot at info/debug level (mGBA logs every DMA transfer), and
    // writing that to logcat costs real emulation speed. Keep warnings/errors only.
    if (level < RETRO_LOG_WARN) return;
    const int prio = level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(prio, "core", fmt, args);
    va_end(args);
}
