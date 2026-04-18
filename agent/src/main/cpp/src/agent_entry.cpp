#include "bytesight_heap.h"
#include <memory>

namespace bytesight {

HeapContext& HeapContext::instance() {
    static HeapContext ctx;
    return ctx;
}

bool HeapContext::init(JavaVM* vm) {
    if (jvmti_ != nullptr) return true;

    jvmtiEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JVMTI_VERSION_1_2) != JNI_OK || env == nullptr) {
        return false;
    }

    jvmtiCapabilities caps = {};
    caps.can_tag_objects = 1;
    caps.can_generate_all_class_hook_events = 0;
    caps.can_get_source_file_name = 1;
    caps.can_get_line_numbers = 1;
    if (env->AddCapabilities(&caps) != JVMTI_ERROR_NONE) {
        return false;
    }

    jvmti_ = env;
    return true;
}

HeapSnapshot* HeapContext::findSnapshot(int64_t snapshot_id) {
    std::lock_guard<std::mutex> lk(mu_);
    auto it = snapshots_.find(snapshot_id);
    return it == snapshots_.end() ? nullptr : it->second.get();
}

}  // namespace bytesight

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    bytesight::HeapContext::instance().init(vm);
    return JNI_VERSION_1_8;
}
