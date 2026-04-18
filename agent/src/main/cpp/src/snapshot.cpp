#include "bytesight_heap.h"

#include <chrono>
#include <cstring>

namespace bytesight {

namespace {

// During heap iteration, class_tag is the tag we assigned to each jclass during
// the pre-pass. We use it to look up the class name quickly.
struct CaptureState {
    HeapSnapshot* snapshot = nullptr;
    int64_t* next_tag = nullptr;
    // class_tag -> class_name (filled during the class pre-tagging pass)
    std::unordered_map<jlong, std::string>* class_name_by_tag = nullptr;
};

jint JNICALL heap_iteration_cb(
    jlong class_tag,
    jlong size,
    jlong* tag_ptr,
    jint /*length*/,
    void* user_data) {
    auto* state = static_cast<CaptureState*>(user_data);

    // Skip the class objects themselves — they're already tagged in the pre-pass
    // and are counted as instances of java.lang.Class in the per-class histogram.
    if (*tag_ptr != 0) {
        return JVMTI_VISIT_OBJECTS;
    }

    const int64_t tag = (*state->next_tag)++;
    *tag_ptr = tag;

    std::string class_name;
    auto it = state->class_name_by_tag->find(class_tag);
    if (it != state->class_name_by_tag->end()) {
        class_name = it->second;
    } else {
        class_name = "<unknown>";
    }

    state->snapshot->tagged.emplace(tag, TaggedObjectMeta{class_name, size, class_tag});
    auto& bucket = state->snapshot->histogram[class_name];
    bucket.instance_count++;
    bucket.shallow_bytes += size;
    state->snapshot->instances_by_class[class_name].push_back(tag);

    state->snapshot->object_count++;
    state->snapshot->total_shallow_bytes += size;
    return JVMTI_VISIT_OBJECTS;
}

// Tag every loaded class with a fresh tag and record class_tag -> name.
// Returns true on success.
bool tag_all_classes(
    jvmtiEnv* jvmti,
    int64_t* next_tag,
    std::unordered_map<jlong, std::string>* out_map) {
    jint class_count = 0;
    jclass* classes = nullptr;
    if (jvmti->GetLoadedClasses(&class_count, &classes) != JVMTI_ERROR_NONE || classes == nullptr) {
        return false;
    }

    for (jint i = 0; i < class_count; ++i) {
        jclass klass = classes[i];
        char* sig = nullptr;
        if (jvmti->GetClassSignature(klass, &sig, nullptr) != JVMTI_ERROR_NONE) {
            continue;
        }
        const std::string name = signature_to_java_name(sig);
        if (sig != nullptr) jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));

        const int64_t tag = (*next_tag)++;
        if (jvmti->SetTag(klass, tag) != JVMTI_ERROR_NONE) {
            continue;
        }
        out_map->emplace(tag, name);
    }

    jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return true;
}

}  // namespace

HeapSnapshot* HeapContext::captureSnapshot(std::string* error_out) {
    if (jvmti_ == nullptr) {
        if (error_out) *error_out = "JVMTI not initialized";
        return nullptr;
    }

    auto snap = std::make_unique<HeapSnapshot>();
    snap->captured_at_millis = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    {
        std::lock_guard<std::mutex> lk(mu_);
        snap->snapshot_id = next_snapshot_id_++;
    }

    if (!tag_all_classes(jvmti_, &next_tag_, &snap->class_name_by_tag)) {
        if (error_out) *error_out = "Failed to enumerate loaded classes";
        return nullptr;
    }

    CaptureState state{};
    state.snapshot = snap.get();
    state.next_tag = &next_tag_;
    state.class_name_by_tag = &snap->class_name_by_tag;

    jvmtiHeapCallbacks cbs = {};
    cbs.heap_iteration_callback = &heap_iteration_cb;

    // JVMTI_HEAP_FILTER_UNTAGGED makes the callback fire only for objects we haven't
    // tagged yet — effectively "fresh objects." We still see tagged class objects
    // because they got a tag in the pre-pass; the callback filters them out via *tag_ptr.
    jvmtiError err = jvmti_->IterateThroughHeap(
        0,            // filter: 0 = visit everything (we filter in the callback)
        nullptr,      // klass filter — nullptr == all classes
        &cbs,
        &state);
    if (err != JVMTI_ERROR_NONE) {
        if (error_out) *error_out = "IterateThroughHeap failed (code " + std::to_string(err) + ")";
        return nullptr;
    }

    HeapSnapshot* ret = snap.get();
    {
        std::lock_guard<std::mutex> lk(mu_);
        snapshots_[snap->snapshot_id] = std::move(snap);
    }
    return ret;
}

}  // namespace bytesight
