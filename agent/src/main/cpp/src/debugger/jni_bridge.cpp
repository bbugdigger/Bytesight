#include "bytesight_debugger.h"

#include <jni.h>

// JNI symbol naming is tied to the Java class:
//   com.bugdigger.agent.debugger.NativeDebuggerBridge
// -> Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_*
//
// Keep the package + class name stable; renaming silently breaks binding.

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_nativeInit(JNIEnv* env, jclass /*cls*/) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return JNI_FALSE;
    return bytesight::DebuggerContext::instance().init(vm) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_nativeIsAvailable(JNIEnv* /*env*/, jclass /*cls*/) {
    return bytesight::DebuggerContext::instance().available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_nativeLastError(JNIEnv* env, jclass /*cls*/) {
    return env->NewStringUTF(bytesight::DebuggerContext::instance().last_error().c_str());
}

// Suspends the given Thread. Returns the JVMTI error code (0 on success,
// 14 = THREAD_SUSPENDED if already suspended, etc.). -1 if the native
// helper isn't initialized.
JNIEXPORT jint JNICALL
Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_nativeSuspendThread(JNIEnv* /*env*/, jclass /*cls*/, jobject thread) {
    auto& ctx = bytesight::DebuggerContext::instance();
    if (!ctx.available() || thread == nullptr) return -1;
    return static_cast<jint>(ctx.jvmti()->SuspendThread(static_cast<jthread>(thread)));
}

// Resumes the given Thread. Returns the JVMTI error code (0 on success,
// 13 = THREAD_NOT_SUSPENDED if not currently suspended, etc.).
JNIEXPORT jint JNICALL
Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_nativeResumeThread(JNIEnv* /*env*/, jclass /*cls*/, jobject thread) {
    auto& ctx = bytesight::DebuggerContext::instance();
    if (!ctx.available() || thread == nullptr) return -1;
    return static_cast<jint>(ctx.jvmti()->ResumeThread(static_cast<jthread>(thread)));
}

}  // extern "C"
