#include "video_output.h"

#include <android/log.h>

#include <algorithm>

#define TAG "VideoOutput"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

const char* kVertex = R"(
attribute vec2 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)";

const char* kFragment = R"(
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
void main() {
    gl_FragColor = texture2D(uTex, vUv);
}
)";

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

inline uint32_t rgba(uint8_t r, uint8_t g, uint8_t b) {
    return 0xFF000000u | (static_cast<uint32_t>(b) << 16) | (static_cast<uint32_t>(g) << 8) | r;
}

}  // namespace

VideoOutput::~VideoOutput() {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyAll();
    if (window_) ANativeWindow_release(window_);
    window_ = nullptr;
}

void VideoOutput::setWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mutex_);
    // The EGL surface must go before the window it renders to is released.
    destroySurface();
    if (window_) ANativeWindow_release(window_);
    window_ = window;
    surfaceDirty_ = true;
}

void VideoOutput::submit(const void* data, unsigned width, unsigned height, size_t pitch, retro_pixel_format fmt) {
    if (!data) return;  // dupe: keep previous frame
    std::lock_guard<std::mutex> lock(mutex_);
    if (width != frameW_ || height != frameH_) {
        frameW_ = width;
        frameH_ = height;
        frame_.assign(static_cast<size_t>(width) * height, 0xFF000000u);
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
    frameDirty_ = true;
}

bool VideoOutput::ensureContext() {
    if (context_ != EGL_NO_CONTEXT) return true;
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }
    const EGLint attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    EGLint count = 0;
    if (!eglChooseConfig(display_, attrs, &config_, 1, &count) || count == 0) {
        LOGE("eglChooseConfig failed");
        return false;
    }
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttrs);
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

bool VideoOutput::ensureSurface() {
    if (surface_ != EGL_NO_SURFACE && !surfaceDirty_) return true;
    destroySurface();
    surfaceDirty_ = false;
    if (!window_) return false;
    surface_ = eglCreateWindowSurface(display_, config_, window_, nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

void VideoOutput::destroySurface() {
    if (surface_ == EGL_NO_SURFACE) return;
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display_, surface_);
    surface_ = EGL_NO_SURFACE;
}

void VideoOutput::destroyAll() {
    if (display_ == EGL_NO_DISPLAY) return;
    if (context_ != EGL_NO_CONTEXT && surface_ != EGL_NO_SURFACE &&
        eglMakeCurrent(display_, surface_, surface_, context_)) {
        if (texture_) glDeleteTextures(1, &texture_);
        if (program_) glDeleteProgram(program_);
    }
    texture_ = 0;
    program_ = 0;
    texW_ = texH_ = 0;
    destroySurface();
    if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
    context_ = EGL_NO_CONTEXT;
    eglTerminate(display_);
    display_ = EGL_NO_DISPLAY;
}

void VideoOutput::present() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!window_ || frame_.empty()) return;
    if (!ensureContext() || !ensureSurface()) return;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        surfaceDirty_ = true;  // try a fresh surface next frame
        return;
    }
    eglSwapInterval(display_, 0);  // the emulation loop does its own pacing

    if (!program_) {
        GLuint vs = compile(GL_VERTEX_SHADER, kVertex);
        GLuint fs = compile(GL_FRAGMENT_SHADER, kFragment);
        program_ = glCreateProgram();
        glAttachShader(program_, vs);
        glAttachShader(program_, fs);
        glLinkProgram(program_);
        glDeleteShader(vs);
        glDeleteShader(fs);
        attrPos_ = glGetAttribLocation(program_, "aPos");
        attrUv_ = glGetAttribLocation(program_, "aUv");
        glGenTextures(1, &texture_);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        frameDirty_ = true;
    }

    glBindTexture(GL_TEXTURE_2D, texture_);
    if (texW_ != frameW_ || texH_ != frameH_) {
        texW_ = frameW_;
        texH_ = frameH_;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, texW_, texH_, 0, GL_RGBA, GL_UNSIGNED_BYTE, frame_.data());
    } else if (frameDirty_) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, texW_, texH_, GL_RGBA, GL_UNSIGNED_BYTE, frame_.data());
    }
    frameDirty_ = false;

    // Letterbox to the frame's aspect ratio.
    EGLint sw = 0, sh = 0;
    eglQuerySurface(display_, surface_, EGL_WIDTH, &sw);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &sh);
    const float frameAspect = static_cast<float>(frameW_) / frameH_;
    int vw = sw, vh = sh;
    if (sw > sh * frameAspect) vw = static_cast<int>(sh * frameAspect);
    else vh = static_cast<int>(sw / frameAspect);
    glViewport(0, 0, sw, sh);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glViewport((sw - vw) / 2, (sh - vh) / 2, vw, vh);

    static const GLfloat quad[] = {
        // x, y, u, v (v flipped: row 0 of the frame is the top)
        -1, -1, 0, 1,  1, -1, 1, 1,  -1, 1, 0, 0,  1, 1, 1, 0,
    };
    glUseProgram(program_);
    glVertexAttribPointer(attrPos_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), quad);
    glVertexAttribPointer(attrUv_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), quad + 2);
    glEnableVertexAttribArray(attrPos_);
    glEnableVertexAttribArray(attrUv_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    if (!eglSwapBuffers(display_, surface_)) {
        LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
        surfaceDirty_ = true;
    }
    // Release the context so another thread may destroy the surface safely.
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
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
