#pragma once

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>

#include <cstdint>
#include <mutex>
#include <vector>

#include "libretro.h"

// Keeps the latest frame as RGBA8888 and draws it to an ANativeWindow with
// OpenGL ES 2 (nearest-neighbour scaling, letterboxed to the frame's aspect).
//
// GL instead of ANativeWindow_lock: CPU-locking the window leaks one GPU fence
// per frame on some Android versions (the process dies with "Too many open
// files" after ~15 minutes). EGL manages buffers and fences itself.
//
// All EGL work happens under mutex_, and the context is released after each
// frame, so the window can be replaced or destroyed from the UI thread at any
// time (including while emulation is paused).
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
    bool ensureContext();        // display + config + context + program (once)
    bool ensureSurface();        // EGL surface for window_ (after a window change)
    void destroySurface();
    void destroyAll();

    std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    bool surfaceDirty_ = false;  // window_ changed since the EGL surface was made

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    GLuint program_ = 0;
    GLuint texture_ = 0;
    GLint attrPos_ = -1, attrUv_ = -1;
    unsigned texW_ = 0, texH_ = 0;

    unsigned frameW_ = 0, frameH_ = 0;
    bool frameDirty_ = false;
    std::vector<uint32_t> frame_;  // memory order R,G,B,A
};
