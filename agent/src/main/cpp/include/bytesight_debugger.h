#ifndef BYTESIGHT_DEBUGGER_H
#define BYTESIGHT_DEBUGGER_H

#include <jvmti.h>
#include <mutex>
#include <string>

namespace bytesight {

// Owns the jvmtiEnv* used by the debugger module. Independent of HeapContext
// — debugger.dll is a separate shared library and does not link against the
// heap helper. JVMTI permits multiple environments per VM, each with its own
// capability set, so the two coexist safely.
class DebuggerContext {
public:
    static DebuggerContext& instance();

    // Acquires the jvmtiEnv*, requests capabilities, and registers any always-on
    // event callbacks. Idempotent; safe to call multiple times.
    bool init(JavaVM* vm);

    jvmtiEnv* jvmti() const { return jvmti_; }
    bool available() const { return jvmti_ != nullptr; }
    const std::string& last_error() const { return last_error_; }

private:
    DebuggerContext() = default;

    jvmtiEnv* jvmti_ = nullptr;
    std::mutex mu_;
    std::string last_error_;
};

}  // namespace bytesight

#endif
