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

}  // extern "C"
