#include "video_output.h"

#include <algorithm>

VideoOutput::~VideoOutput() { setWindow(nullptr); }

void VideoOutput::setWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_) ANativeWindow_release(window_);
    window_ = window;
    bufferScale_ = 0;  // force geometry update
    if (window_) {
        windowW_ = ANativeWindow_getWidth(window_);
        windowH_ = ANativeWindow_getHeight(window_);
    }
}

static inline uint32_t rgba(uint8_t r, uint8_t g, uint8_t b) {
    return 0xFF000000u | (static_cast<uint32_t>(b) << 16) | (static_cast<uint32_t>(g) << 8) | r;
}

void VideoOutput::submit(const void* data, unsigned width, unsigned height, size_t pitch, retro_pixel_format fmt) {
    if (!data) return;  // dupe: keep previous frame
    std::lock_guard<std::mutex> lock(mutex_);
    if (width != frameW_ || height != frameH_) {
        frameW_ = width;
        frameH_ = height;
        frame_.assign(static_cast<size_t>(width) * height, 0xFF000000u);
        bufferScale_ = 0;
    }
    const auto* src = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; ++y) {
        uint32_t* dst = &frame_[static_cast<size_t>(y) * width];
        const uint8_t* row = src + y * pitch;
        switch (fmt) {
            case RETRO_PIXEL_FORMAT_RGB565: {
                auto* p = reinterpret_cast<const uint16_t*>(row);
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t c = p[x];
                    uint8_t r = (c >> 11) & 0x1F, g = (c >> 5) & 0x3F, b = c & 0x1F;
                    dst[x] = rgba((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2));
                }
                break;
            }
            case RETRO_PIXEL_FORMAT_0RGB1555: {
                auto* p = reinterpret_cast<const uint16_t*>(row);
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t c = p[x];
                    uint8_t r = (c >> 10) & 0x1F, g = (c >> 5) & 0x1F, b = c & 0x1F;
                    dst[x] = rgba((r << 3) | (r >> 2), (g << 3) | (g >> 2), (b << 3) | (b >> 2));
                }
                break;
            }
            default: {  // XRGB8888
                auto* p = reinterpret_cast<const uint32_t*>(row);
                for (unsigned x = 0; x < width; ++x) {
                    uint32_t c = p[x];
                    dst[x] = rgba((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
                }
                break;
            }
        }
    }
}

void VideoOutput::present() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!window_ || frame_.empty()) return;

    if (bufferScale_ == 0) {
        int s = std::max(1, std::min(windowW_ / static_cast<int>(frameW_), windowH_ / static_cast<int>(frameH_)));
        s = std::min(s, 8);
        bufferScale_ = s;
        ANativeWindow_setBuffersGeometry(window_, frameW_ * s, frameH_ * s, WINDOW_FORMAT_RGBA_8888);
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window_, &buf, nullptr) != 0) return;
    const int s = bufferScale_;
    const unsigned outW = std::min<unsigned>(frameW_ * s, buf.width);
    const unsigned outH = std::min<unsigned>(frameH_ * s, buf.height);
    auto* dstBase = static_cast<uint32_t*>(buf.bits);
    for (unsigned y = 0; y < outH; ++y) {
        const uint32_t* src = &frame_[static_cast<size_t>(y / s) * frameW_];
        uint32_t* dst = dstBase + static_cast<size_t>(y) * buf.stride;
        if (s == 1) {
            std::copy(src, src + outW, dst);
        } else {
            for (unsigned x = 0; x < outW; ++x) dst[x] = src[x / s];
        }
    }
    ANativeWindow_unlockAndPost(window_);
}

bool VideoOutput::snapshotArgb(std::vector<uint32_t>& out, unsigned& width, unsigned& height) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (frame_.empty()) return false;
    width = frameW_;
    height = frameH_;
    out.resize(frame_.size());
    for (size_t i = 0; i < frame_.size(); ++i) {
        uint32_t c = frame_[i];  // A B G R (high->low)
        out[i] = (c & 0xFF00FF00u) | ((c & 0xFFu) << 16) | ((c >> 16) & 0xFFu);
    }
    return true;
}
