// JNI entry points for com.pokeemu.core.NativeBridge.
#include <android/native_window_jni.h>
#include <jni.h>

#include <string>
#include <vector>

#include "session.h"

namespace {

Session* gSession = nullptr;

std::string toString(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::vector<uint8_t> toBytes(JNIEnv* env, jbyteArray arr) {
    std::vector<uint8_t> out(env->GetArrayLength(arr));
    env->GetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray toJava(JNIEnv* env, const std::vector<uint8_t>& bytes) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return arr;
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_pokeemu_core_NativeBridge_##name

JNI_FN(jboolean, nativeCreate)(JNIEnv* env, jobject, jstring coreLib, jstring systemDir, jstring saveDir) {
    if (gSession) return JNI_FALSE;
    gSession = Session::create(toString(env, coreLib), toString(env, systemDir), toString(env, saveDir));
    return gSession ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jboolean, nativeLoadGame)(JNIEnv* env, jobject, jstring romPath, jstring savePath) {
    if (!gSession) return JNI_FALSE;
    return gSession->loadGame(toString(env, romPath), toString(env, savePath)) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, nativeStart)(JNIEnv*, jobject) {
    if (gSession) gSession->start();
}

JNI_FN(void, nativePause)(JNIEnv*, jobject) {
    if (gSession) gSession->pause();
}

JNI_FN(void, nativeResume)(JNIEnv*, jobject) {
    if (gSession) gSession->resume();
}

JNI_FN(void, nativeDestroy)(JNIEnv*, jobject) {
    delete gSession;  // stops thread, flushes save RAM, unloads core
    gSession = nullptr;
}

JNI_FN(void, nativeSetSurface)(JNIEnv* env, jobject, jobject surface) {
    if (!gSession) return;
    gSession->setWindow(surface ? ANativeWindow_fromSurface(env, surface) : nullptr);
}

JNI_FN(void, nativeSetButtons)(JNIEnv*, jobject, jint mask) {
    if (gSession) gSession->setButtons(static_cast<uint32_t>(mask));
}

JNI_FN(void, nativeSetPointer)(JNIEnv*, jobject, jint x, jint y, jboolean pressed) {
    if (gSession) gSession->setPointer(x, y, pressed == JNI_TRUE);
}

JNI_FN(void, nativeSetVariable)(JNIEnv* env, jobject, jstring key, jstring value) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    LibretroCore::setVariable(k, v);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(value, v);
}

JNI_FN(void, nativeSetSpeed)(JNIEnv*, jobject, jint multiplier) {
    if (gSession) gSession->setSpeed(multiplier);
}

JNI_FN(void, nativeReset)(JNIEnv*, jobject) {
    if (gSession) gSession->reset();
}

JNI_FN(jbyteArray, nativeSaveState)(JNIEnv* env, jobject) {
    if (!gSession) return nullptr;
    auto state = gSession->saveState();
    return state.empty() ? nullptr : toJava(env, state);
}

JNI_FN(jboolean, nativeLoadState)(JNIEnv* env, jobject, jbyteArray state) {
    if (!gSession || !state) return JNI_FALSE;
    return gSession->loadState(toBytes(env, state)) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jboolean, nativeFlushSave)(JNIEnv*, jobject) {
    return gSession && gSession->flushSaveRam(true) ? JNI_TRUE : JNI_FALSE;
}

// Returns [width, height, argb...] or null.
JNI_FN(jintArray, nativeSnapshot)(JNIEnv* env, jobject) {
    if (!gSession) return nullptr;
    std::vector<uint32_t> argb;
    unsigned w = 0, h = 0;
    if (!gSession->snapshot(argb, w, h)) return nullptr;
    jintArray arr = env->NewIntArray(static_cast<jsize>(argb.size() + 2));
    jint dims[2] = {static_cast<jint>(w), static_cast<jint>(h)};
    env->SetIntArrayRegion(arr, 0, 2, dims);
    env->SetIntArrayRegion(arr, 2, static_cast<jsize>(argb.size()), reinterpret_cast<const jint*>(argb.data()));
    return arr;
}

JNI_FN(jbyteArray, nativeReadMemory)(JNIEnv* env, jobject, jint region, jlong offset, jint length) {
    if (!gSession || offset < 0 || length <= 0) return nullptr;
    auto bytes = gSession->readMemory(static_cast<unsigned>(region), static_cast<size_t>(offset),
                                      static_cast<size_t>(length));
    return bytes.empty() ? nullptr : toJava(env, bytes);
}

JNI_FN(jbyteArray, nativeReadBus)(JNIEnv* env, jobject, jlong address, jint length) {
    if (!gSession || address < 0 || length <= 0) return nullptr;
    auto bytes = gSession->readBus(static_cast<uint32_t>(address), static_cast<size_t>(length));
    return bytes.empty() ? nullptr : toJava(env, bytes);
}

JNI_FN(jdouble, nativeFps)(JNIEnv*, jobject) { return gSession ? gSession->fps() : 0.0; }
