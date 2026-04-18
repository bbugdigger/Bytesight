#ifndef BYTESIGHT_HEAP_H
#define BYTESIGHT_HEAP_H

#include <jvmti.h>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>
#include <mutex>

namespace bytesight {

struct TaggedObjectMeta {
    std::string class_name;   // fully-qualified JLS form, e.g. "java.lang.String"
    int64_t shallow_size;
    jlong class_tag;          // Tag of the owning jclass (0 if unknown — e.g. the class object itself)
};

struct ClassHistogramBucket {
    int64_t instance_count = 0;
    int64_t shallow_bytes = 0;
};

struct HeapSnapshot {
    int64_t snapshot_id = 0;
    int64_t object_count = 0;
    int64_t total_shallow_bytes = 0;
    int64_t captured_at_millis = 0;
    // tag -> meta for every live (non-class) object
    std::unordered_map<int64_t, TaggedObjectMeta> tagged;
    // class name -> aggregate (built during capture for fast histogram queries)
    std::unordered_map<std::string, ClassHistogramBucket> histogram;
    // class name -> tags of every instance of that class (for fast ListInstances)
    std::unordered_map<std::string, std::vector<int64_t>> instances_by_class;
    // class tag -> class name (for Phase 2 object reader; class objects share these tags)
    std::unordered_map<jlong, std::string> class_name_by_tag;
};

// Describes one field read from an object during GetObject. Mirrors proto FieldValue.
struct FieldValueNative {
    enum class Kind { INT, DOUBLE, STRING, REF, NULLVAL };
    std::string name;
    std::string declared_type;
    Kind kind = Kind::NULLVAL;
    int64_t int_value = 0;
    double double_value = 0.0;
    std::string string_value;
    int64_t ref_tag = 0;
    bool is_static = false;
    bool is_null = false;
};

struct ReferenceEdgeNative {
    std::string field_name;
    int64_t target_tag = 0;
    std::string target_class_name;
};

struct ObjectDetailNative {
    int64_t tag = 0;
    std::string class_name;
    int64_t shallow_bytes = 0;
    bool found = false;
    std::string error;
    std::vector<FieldValueNative> fields;
    std::vector<ReferenceEdgeNative> outgoing_refs;
};

struct InstanceSummaryNative {
    int64_t tag = 0;
    std::string class_name;
    int64_t shallow_bytes = 0;
    std::string preview;
};

// Phase 3: Value search result
struct ValueMatchNative {
    int64_t tag = 0;
    std::string class_name;
    std::string matched_field;   // empty for string-contains mode
    std::string matched_value;   // the actual value (truncated)
};

// Phase 3: Duplicate string group
struct DuplicateStringGroupNative {
    std::string value;           // the duplicated content (truncated to 200 chars)
    int32_t count = 0;
    int64_t wasted_bytes = 0;    // (count - 1) * shallow_size
    std::vector<int64_t> example_tags;
};

class HeapContext {
public:
    static HeapContext& instance();

    bool init(JavaVM* vm);
    jvmtiEnv* jvmti() const { return jvmti_; }
    bool available() const { return jvmti_ != nullptr; }

    // Returns the new snapshot or nullptr if capture failed. Thread-safe.
    HeapSnapshot* captureSnapshot(std::string* error_out);

    // Access an existing snapshot by id (nullptr if unknown).
    HeapSnapshot* findSnapshot(int64_t snapshot_id);

    // List instances of `class_name` in `snapshot_id`, up to `limit` (0 means no limit).
    // Requires a JNI environment on the calling thread for preview generation.
    std::vector<InstanceSummaryNative> listInstances(
        JNIEnv* env,
        int64_t snapshot_id,
        const std::string& class_name,
        int32_t limit);

    // Read one tagged object's shallow state. Returns {found=false,...} if the tag is unknown.
    ObjectDetailNative getObject(JNIEnv* env, int64_t snapshot_id, int64_t tag);

    // Phase 3: Search for string values containing `needle` across all objects in the snapshot.
    std::vector<ValueMatchNative> searchStringContains(
        JNIEnv* env, int64_t snapshot_id, const std::string& needle, int32_t limit);

    // Phase 3: Search for objects where field `field_name` of class `class_name` equals `value_str`.
    std::vector<ValueMatchNative> searchFieldEquals(
        JNIEnv* env, int64_t snapshot_id,
        const std::string& class_name, const std::string& field_name,
        const std::string& value_str, int32_t limit);

    // Phase 3: Find duplicate java.lang.String instances.
    std::vector<DuplicateStringGroupNative> findDuplicateStrings(
        JNIEnv* env, int64_t snapshot_id,
        int32_t min_count, int32_t min_length, int32_t limit_groups);

private:
    HeapContext() = default;

    jvmtiEnv* jvmti_ = nullptr;
    std::mutex mu_;
    int64_t next_snapshot_id_ = 1;
    int64_t next_tag_ = 1;
    std::unordered_map<int64_t, std::unique_ptr<HeapSnapshot>> snapshots_;
};

// JVMTI signature (e.g. "Ljava/lang/String;") -> Java name (e.g. "java.lang.String").
std::string signature_to_java_name(const char* signature);

}  // namespace bytesight

#endif
