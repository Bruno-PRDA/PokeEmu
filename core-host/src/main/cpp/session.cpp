#include "session.h"

#include <cstring>

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>

#define TAG "Session"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

Session* Session::current_ = nullptr;

// mGBA allocates a fixed 128 KiB save buffer (Flash 1M, the largest GBA save
// type) and may report size 0 until the save type is detected.
static constexpr size_t kMaxGbaSave = 128 * 1024;
static constexpr int kFlushIntervalFrames = 120;  // ~2 s

Session* Session::create(const std::string& coreLibrary, const std::string& systemDir, const std::string& saveDir) {
    if (current_) {
        LOGE("session already exists");
        return nullptr;
    }
    auto* s = new Session();
    LibretroCore::Callbacks cb;
    cb.video = &Session::onVideo;
    cb.audioBatch = &Session::onAudio;
    cb.inputState = &Session::onInput;
    current_ = s;
    if (!s->core_.open(coreLibrary, systemDir, saveDir, cb)) {
        current_ = nullptr;
        delete s;
        return nullptr;
    }
    return s;
}

Session::~Session() {
    stop();
    core_.close();
    if (romMap_) munmap(romMap_, romMapSize_);
    if (current_ == this) current_ = nullptr;
}

static bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

bool Session::loadGame(const std::string& romPath, const std::string& savePath,
                       const std::vector<std::pair<size_t, std::vector<uint8_t>>>& patches) {
    std::lock_guard<std::mutex> lock(coreMutex_);
    int fd = open(romPath.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("cannot open ROM %s", romPath.c_str());
        return false;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return false;
    }
    // Private copy-on-write mapping: the core may patch its buffer without touching the file.
    void* map = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) {
        LOGE("mmap of %zu bytes failed", static_cast<size_t>(st.st_size));
        return false;
    }
    romMap_ = map;
    romMapSize_ = static_cast<size_t>(st.st_size);
    // Cheats that change game data: written into the copy-on-write mapping, never the file.
    for (const auto& p : patches) {
        if (p.first + p.second.size() <= romMapSize_) {
            std::memcpy(static_cast<uint8_t*>(romMap_) + p.first, p.second.data(), p.second.size());
        }
    }
    if (!core_.loadGame(romMap_, romMapSize_, romPath)) return false;
    savePath_ = savePath;

    std::vector<uint8_t> sav;
    if (!savePath_.empty() && readFile(savePath_, sav) && !sav.empty()) {
        size_t size = 0;
        uint8_t* ram = core_.memoryData(RETRO_MEMORY_SAVE_RAM, &size);
        size_t capacity = size > 0 ? size : kMaxGbaSave;
        if (ram) {
            size_t n = std::min(sav.size(), capacity);
            std::copy(sav.begin(), sav.begin() + n, ram);
            LOGI("loaded %zu bytes of save RAM from %s", n, savePath_.c_str());
        }
        lastSavedRam_ = sav;
    }
    return true;
}

void Session::start() {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (running_) return;
        running_ = true;
        paused_ = false;
    }
    audio_.start(core_.avInfo().timing.sample_rate);
    thread_ = std::thread(&Session::loop, this);
}

void Session::pause() {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        paused_ = true;
    }
    audio_.pause();
    flushSaveRam();
}

void Session::resume() {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        paused_ = false;
    }
    audio_.resume();
    stateCv_.notify_all();
}

void Session::stop() {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (!running_) return;
        running_ = false;
    }
    stateCv_.notify_all();
    if (thread_.joinable()) thread_.join();
    audio_.stop();
    flushSaveRam();
}

void Session::reset() {
    std::lock_guard<std::mutex> lock(coreMutex_);
    core_.reset();
}

bool Session::setCheats(const std::vector<std::string>& codes) {
    std::lock_guard<std::mutex> lock(coreMutex_);  // between frames
    return core_.setCheats(codes);
}

void Session::loop() {
    using clock = std::chrono::steady_clock;
    const double fps = core_.avInfo().timing.fps > 0 ? core_.avInfo().timing.fps : 60.0;
    const auto frameTime = std::chrono::duration_cast<clock::duration>(std::chrono::duration<double>(1.0 / fps));
    auto next = clock::now();
    int framesSinceFlush = 0;
    // Speed readout: emulated frames per second, logged every few seconds.
    auto statsStart = clock::now();
    int statsFrames = 0;

    while (true) {
        {
            std::unique_lock<std::mutex> lock(stateMutex_);
            if (paused_) {
                stateCv_.wait(lock, [this] { return !paused_ || !running_; });
                next = clock::now();
            }
            if (!running_) break;
        }

        const int speed = speed_.load();
        audioMuted_ = speed > 1;
        {
            std::lock_guard<std::mutex> lock(coreMutex_);
            for (int i = 0; i < speed; ++i) core_.runFrame();
        }
        video_.present();

        statsFrames += speed;
        if (clock::now() - statsStart >= std::chrono::seconds(5)) {
            const double secs = std::chrono::duration<double>(clock::now() - statsStart).count();
            LOGI("speed: %.1f fps (target %.1f, %d%%)", statsFrames / secs, fps,
                 static_cast<int>(100.0 * statsFrames / secs / fps));
            statsStart = clock::now();
            statsFrames = 0;
        }

        if (++framesSinceFlush >= kFlushIntervalFrames) {
            framesSinceFlush = 0;
            flushSaveRam();
        }

        next += frameTime;
        auto now = clock::now();
        if (next > now) {
            std::this_thread::sleep_until(next);
        } else if (now - next > frameTime * 5) {
            next = now;  // fell far behind (e.g. app was backgrounded): don't spiral
        }
    }
}

bool Session::flushSaveRam(bool force) {
    std::lock_guard<std::mutex> saveLock(saveMutex_);  // UI thread and emu thread both flush
    if (savePath_.empty()) return true;
    std::vector<uint8_t> ram;
    {
        std::lock_guard<std::mutex> lock(coreMutex_);
        size_t size = 0;
        uint8_t* data = core_.memoryData(RETRO_MEMORY_SAVE_RAM, &size);
        if (!data || size == 0) return true;  // no save type detected yet
        ram.assign(data, data + size);
    }
    if (!force && ram == lastSavedRam_) return true;
    // No save file yet and the RAM is still blank: the game never saved, so don't create one.
    if (lastSavedRam_.empty() &&
        std::all_of(ram.begin(), ram.end(), [&](uint8_t b) { return b == ram[0]; }) &&
        (ram[0] == 0xFF || ram[0] == 0x00)) {
        return true;
    }

    // Write to a temp file then rename, so a crash never leaves a half-written save.
    const std::string tmp = savePath_ + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) {
            LOGE("cannot open %s", tmp.c_str());
            return false;
        }
        out.write(reinterpret_cast<const char*>(ram.data()), static_cast<std::streamsize>(ram.size()));
        if (!out) return false;
    }
    if (std::rename(tmp.c_str(), savePath_.c_str()) != 0) {
        LOGE("rename to %s failed", savePath_.c_str());
        return false;
    }
    lastSavedRam_ = std::move(ram);
    return true;
}

std::vector<uint8_t> Session::saveState() {
    std::lock_guard<std::mutex> lock(coreMutex_);
    return core_.serialize();
}

bool Session::loadState(const std::vector<uint8_t>& state) {
    bool ok;
    {
        std::lock_guard<std::mutex> lock(coreMutex_);
        ok = core_.unserialize(state);
    }
    if (ok) audio_.clear();
    return ok;
}

std::vector<uint8_t> Session::readMemory(unsigned region, size_t offset, size_t length) {
    std::lock_guard<std::mutex> lock(coreMutex_);
    size_t size = 0;
    uint8_t* data = core_.memoryData(region, &size);
    if (!data || offset >= size) return {};
    length = std::min(length, size - offset);
    return std::vector<uint8_t>(data + offset, data + offset + length);
}

std::vector<uint8_t> Session::readBus(uint32_t address, size_t length) {
    std::lock_guard<std::mutex> lock(coreMutex_);  // consistent snapshot between frames
    size_t available = 0;
    uint8_t* p = core_.busAddress(address, &available);
    if (!p) return {};
    length = std::min(length, available);
    return std::vector<uint8_t>(p, p + length);
}

bool Session::writeBus(uint32_t address, const std::vector<uint8_t>& data) {
    std::lock_guard<std::mutex> lock(coreMutex_);  // never in the middle of a frame
    size_t available = 0;
    uint8_t* p = core_.busAddress(address, &available);
    if (!p || data.size() > available) return false;
    std::memcpy(p, data.data(), data.size());
    return true;
}

// ---- core callbacks (emulation thread) --------------------------------------

void Session::onVideo(const void* data, unsigned w, unsigned h, size_t pitch, retro_pixel_format fmt) {
    if (current_) current_->video_.submit(data, w, h, pitch, fmt);
}

size_t Session::onAudio(const int16_t* data, size_t frames) {
    if (current_ && !current_->audioMuted_) current_->audio_.push(data, frames);
    return frames;
}

int16_t Session::onInput(unsigned port, unsigned device, unsigned, unsigned id) {
    if (!current_ || port != 0) return 0;
    if (device == RETRO_DEVICE_POINTER) {
        switch (id) {
            case RETRO_DEVICE_ID_POINTER_X: return static_cast<int16_t>(current_->pointerX_.load());
            case RETRO_DEVICE_ID_POINTER_Y: return static_cast<int16_t>(current_->pointerY_.load());
            case RETRO_DEVICE_ID_POINTER_PRESSED: return current_->pointerPressed_.load() ? 1 : 0;
            case RETRO_DEVICE_ID_POINTER_COUNT: return current_->pointerPressed_.load() ? 1 : 0;
            default: return 0;
        }
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    const uint32_t mask = current_->buttons_.load();
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(mask & 0xFFFF);
    return (mask >> id) & 1 ? 1 : 0;
}
