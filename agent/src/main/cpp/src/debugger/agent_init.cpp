#include "bytesight_debugger.h"

#include <jni.h>

namespace bytesight {

DebuggerContext& DebuggerContext::instance() {
    static DebuggerContext ctx;
    return ctx;
}

bool DebuggerContext::init(JavaVM* vm) {
    std::lock_guard<std::mutex> lk(mu_);
    if (jvmti_ != nullptr) return true;

    jvmtiEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JVMTI_VERSION_1_2) != JNI_OK || env == nullptr) {
        last_error_ = "GetEnv(JVMTI_VERSION_1_2) failed";
        return false;
    }

    // Limited to capabilities that work in JVMTI Phase: live (post-Agent_OnAttach).
    // HotSpot refuses can_generate_breakpoint_events, can_generate_single_step_events,
    // can_generate_frame_pop_events, can_generate_method_entry/exit_events, and
    // can_access_local_variables in live phase — these are OnLoad-only. The Phase 2
    // debugger therefore uses ByteBuddy (no special caps) for its breakpoint and
    // stepping mechanism, and JVMTI only for utility:
    //   - can_suspend          — real Pause RPC (Phase 1's Pause was a no-op stub).
    //   - can_get_line_numbers — fast native line-table queries used by LineResolver.
    jvmtiCapabilities caps = {};
    caps.can_suspend = 1;
    caps.can_get_line_numbers = 1;

    jvmtiError err = env->AddCapabilities(&caps);
    if (err != JVMTI_ERROR_NONE) {
        last_error_ = "AddCapabilities failed with JVMTI error " + std::to_string(err);
        return false;
    }

    jvmti_ = env;
    return true;
}

}  // namespace bytesight

// Triggered by VirtualMachine.loadAgentPath() — the path Bytesight uses to
// acquire JVMTI capabilities after the Java agent is attached. JNI symbol
// resolution is handled separately via System.load on the Java side, which
// loads the same DLL file (single OS mapping, two JVM-level registrations:
// one as a JVMTI agent, one as a JNI library).
extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* /*options*/, void* /*reserved*/) {
    return bytesight::DebuggerContext::instance().init(vm) ? JNI_OK : JNI_ERR;
}

// Triggered by -agentpath at JVM startup. Same setup; AddCapabilities never
// fails here since OnLoad is the most permissive phase.
extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    return Agent_OnAttach(vm, options, reserved);
}

// Triggered when the Java agent calls System.load on the same DLL file.
// Idempotent with init(); we just want to ensure the JVM treats this DLL as
// part of its native library list so JNI symbol auto-resolution finds the
// Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_* exports.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    return JNI_VERSION_1_8;
}
