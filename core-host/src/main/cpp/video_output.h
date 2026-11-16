#pragma once

#include <android/native_window.h>

#include <cstdint>
#include <mutex>
#include <vector>

#include "libretro.h"

// Keeps the latest frame as RGBA8888 and presents it to an ANativeWindow,
// upscaled by the largest integer factor that fits (nearest neighbour) so
// pixels stay sharp; the compositor handles the small remainder.
class VideoOutput {
public:
    ~VideoOutput();

    // Takes ownership of one reference to window (may be null to detach).
    void setWindow(ANativeWindow* window);
    // Called from the core's video callback. data == nullptr means "duplicate frame".
    void submit(const void* data, unsigned width, unsigned height, size_t pitch, retro_pixel_format fmt);
    void present();
    // Copies the current frame as ARGB (Android Bitmap order). Returns false if none.
    bool snapshotArgb(std::vector<uint32_t>& out, unsigned& width, unsigned& height);

private:
    std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    int windowW_ = 0, windowH_ = 0;
    int bufferScale_ = 0;
    unsigned frameW_ = 0, frameH_ = 0;
    std::vector<uint32_t> frame_;  // ABGR in memory order R,G,B,A (RGBA_8888)
};
