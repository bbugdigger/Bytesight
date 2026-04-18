#include "bytesight_heap.h"

#include <jni.h>
#include <algorithm>
#include <cstring>
#include <sstream>
#include <unordered_map>

// Implements HeapContext::searchStringContains, HeapContext::searchFieldEquals,
// and HeapContext::findDuplicateStrings (Phase 3).

namespace bytesight {

namespace {

constexpr int kMaxMatchValueLen = 200;
constexpr int kMaxDupExampleTags = 5;

// Look up the jobject for a tag via GetObjectsWithTags (same helper as object_reader).
jobject get_object_for_tag_search(jvmtiEnv* jvmti, JNIEnv* env, jlong tag) {
    if (tag == 0) return nullptr;
    jint count = 0;
    jobject* objects = nullptr;
    jlong* result_tags = nullptr;
    jlong query_tags[1] = { tag };
    jvmtiError err = jvmti->GetObjectsWithTags(1, query_tags, &count, &objects, &result_tags);
    if (err != JVMTI_ERROR_NONE) return nullptr;

    jobject ret = nullptr;
    if (count > 0 && objects != nullptr && objects[0] != nullptr) {
        ret = env->NewLocalRef(objects[0]);
    }

    if (objects != nullptr) jvmti->Deallocate(reinterpret_cast<unsigned char*>(objects));
    if (result_tags != nullptr) jvmti->Deallocate(reinterpret_cast<unsigned char*>(result_tags));
    return ret;
}

// Read string value via JNI (same approach as object_reader).
std::string read_string_val(JNIEnv* env, jobject str_obj, int max_len) {
    if (str_obj == nullptr) return std::string();
    auto jstr = reinterpret_cast<jstring>(str_obj);
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) {
        env->ExceptionClear();
        return std::string();
    }
    std::string s(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    if (static_cast<int>(s.size()) > max_len) {
        s.resize(max_len);
    }
    return s;
}

// Case-insensitive contains check.
bool contains_ci(const std::string& haystack, const std::string& needle) {
    if (needle.empty()) return true;
    if (haystack.size() < needle.size()) return false;
    auto it = std::search(
        haystack.begin(), haystack.end(),
        needle.begin(), needle.end(),
        [](char a, char b) { return std::tolower(static_cast<unsigned char>(a)) == std::tolower(static_cast<unsigned char>(b)); }
    );
    return it != haystack.end();
}

}  // namespace

std::vector<ValueMatchNative> HeapContext::searchStringContains(
    JNIEnv* env, int64_t snapshot_id, const std::string& needle, int32_t limit) {
    std::vector<ValueMatchNative> out;
    if (needle.empty()) return out;

    HeapSnapshot* snap = findSnapshot(snapshot_id);
    if (snap == nullptr) return out;

    const int32_t cap = (limit > 0) ? limit : 500;

    // Iterate all tagged objects that are java.lang.String
    auto class_it = snap->instances_by_class.find("java.lang.String");
    if (class_it == snap->instances_by_class.end()) return out;

    for (const int64_t tag : class_it->second) {
        if (static_cast<int32_t>(out.size()) >= cap) break;

        jobject obj = get_object_for_tag_search(jvmti_, env, tag);
        if (obj == nullptr) continue;

        std::string val = read_string_val(env, obj, kMaxMatchValueLen);
        env->DeleteLocalRef(obj);

        if (contains_ci(val, needle)) {
            ValueMatchNative m{};
            m.tag = tag;
            m.class_name = "java.lang.String";
            m.matched_value = val;
            out.push_back(std::move(m));
        }
    }

    return out;
}

std::vector<ValueMatchNative> HeapContext::searchFieldEquals(
    JNIEnv* env, int64_t snapshot_id,
    const std::string& class_name, const std::string& field_name,
    const std::string& value_str, int32_t limit) {
    std::vector<ValueMatchNative> out;
    if (class_name.empty() || field_name.empty()) return out;

    HeapSnapshot* snap = findSnapshot(snapshot_id);
    if (snap == nullptr) return out;

    const int32_t cap = (limit > 0) ? limit : 500;

    auto class_it = snap->instances_by_class.find(class_name);
    if (class_it == snap->instances_by_class.end()) return out;

    for (const int64_t tag : class_it->second) {
        if (static_cast<int32_t>(out.size()) >= cap) break;

        jobject obj = get_object_for_tag_search(jvmti_, env, tag);
        if (obj == nullptr) continue;

        jclass klass = env->GetObjectClass(obj);
        if (klass == nullptr) { env->DeleteLocalRef(obj); continue; }

        // Walk class hierarchy to find the field
        bool matched = false;
        jclass cur = klass;
        bool first = true;
        while (cur != nullptr && !matched) {
            jint field_count = 0;
            jfieldID* fields = nullptr;
            if (jvmti_->GetClassFields(cur, &field_count, &fields) == JVMTI_ERROR_NONE && fields != nullptr) {
                for (jint i = 0; i < field_count && !matched; ++i) {
                    char* fname = nullptr;
                    char* fsig = nullptr;
                    if (jvmti_->GetFieldName(cur, fields[i], &fname, &fsig, nullptr) != JVMTI_ERROR_NONE) continue;

                    if (fname != nullptr && field_name == fname) {
                        // Found the field — read its value and compare as string
                        std::string actual_val;
                        if (fsig != nullptr) {
                            char tc = fsig[0];
                            if (tc == 'L' && std::string(fsig) == "Ljava/lang/String;") {
                                jobject sobj = env->GetObjectField(obj, fields[i]);
                                if (env->ExceptionCheck()) env->ExceptionClear();
                                actual_val = read_string_val(env, sobj, kMaxMatchValueLen);
                                if (sobj != nullptr) env->DeleteLocalRef(sobj);
                            } else if (tc != 'L' && tc != '[') {
                                // Primitive — read and convert to string
                                std::ostringstream oss;
                                switch (tc) {
                                    case 'Z': oss << (env->GetBooleanField(obj, fields[i]) ? "true" : "false"); break;
                                    case 'B': oss << static_cast<int>(env->GetByteField(obj, fields[i])); break;
                                    case 'C': oss << static_cast<int>(env->GetCharField(obj, fields[i])); break;
                                    case 'S': oss << env->GetShortField(obj, fields[i]); break;
                                    case 'I': oss << env->GetIntField(obj, fields[i]); break;
                                    case 'J': oss << env->GetLongField(obj, fields[i]); break;
                                    case 'F': oss << env->GetFloatField(obj, fields[i]); break;
                                    case 'D': oss << env->GetDoubleField(obj, fields[i]); break;
                                    default: break;
                                }
                                actual_val = oss.str();
                            }
                        }

                        if (actual_val == value_str) {
                            ValueMatchNative m{};
                            m.tag = tag;
                            m.class_name = class_name;
                            m.matched_field = field_name;
                            m.matched_value = actual_val;
                            out.push_back(std::move(m));
                            matched = true;
                        }
                    }

                    if (fname) jvmti_->Deallocate(reinterpret_cast<unsigned char*>(fname));
                    if (fsig) jvmti_->Deallocate(reinterpret_cast<unsigned char*>(fsig));
                }
                jvmti_->Deallocate(reinterpret_cast<unsigned char*>(fields));
            }

            jclass super_cls = env->GetSuperclass(cur);
            if (!first) env->DeleteLocalRef(cur);
            first = false;
            cur = super_cls;
            if (cur == nullptr) break;
            char* cur_sig = nullptr;
            if (jvmti_->GetClassSignature(cur, &cur_sig, nullptr) == JVMTI_ERROR_NONE && cur_sig != nullptr) {
                bool is_object = std::strcmp(cur_sig, "Ljava/lang/Object;") == 0;
                jvmti_->Deallocate(reinterpret_cast<unsigned char*>(cur_sig));
                if (is_object) { env->DeleteLocalRef(cur); break; }
            }
        }

        env->DeleteLocalRef(klass);
        env->DeleteLocalRef(obj);
    }

    return out;
}

std::vector<DuplicateStringGroupNative> HeapContext::findDuplicateStrings(
    JNIEnv* env, int64_t snapshot_id,
    int32_t min_count, int32_t min_length, int32_t limit_groups) {
    std::vector<DuplicateStringGroupNative> out;

    HeapSnapshot* snap = findSnapshot(snapshot_id);
    if (snap == nullptr) return out;

    if (min_count < 2) min_count = 2;
    if (limit_groups <= 0) limit_groups = 100;

    auto class_it = snap->instances_by_class.find("java.lang.String");
    if (class_it == snap->instances_by_class.end()) return out;

    // Collect string value -> list of (tag, shallow_size)
    struct TagAndSize { int64_t tag; int64_t shallow_size; };
    std::unordered_map<std::string, std::vector<TagAndSize>> value_map;

    for (const int64_t tag : class_it->second) {
        jobject obj = get_object_for_tag_search(jvmti_, env, tag);
        if (obj == nullptr) continue;

        std::string val = read_string_val(env, obj, kMaxMatchValueLen);
        env->DeleteLocalRef(obj);

        if (static_cast<int32_t>(val.size()) < min_length) continue;

        auto mit = snap->tagged.find(tag);
        int64_t sz = (mit != snap->tagged.end()) ? mit->second.shallow_size : 0;
        value_map[val].push_back({tag, sz});
    }

    // Filter groups by min_count, sort by wasted bytes desc
    struct GroupEntry {
        std::string value;
        int32_t count;
        int64_t wasted;
        std::vector<int64_t> tags;
    };
    std::vector<GroupEntry> groups;
    groups.reserve(value_map.size());

    for (auto& kv : value_map) {
        if (static_cast<int32_t>(kv.second.size()) < min_count) continue;
        GroupEntry g;
        g.value = kv.first;
        g.count = static_cast<int32_t>(kv.second.size());
        int64_t avg_size = 0;
        for (const auto& ts : kv.second) avg_size += ts.shallow_size;
        avg_size = kv.second.empty() ? 0 : avg_size / static_cast<int64_t>(kv.second.size());
        g.wasted = static_cast<int64_t>(g.count - 1) * avg_size;
        for (size_t i = 0; i < std::min<size_t>(kv.second.size(), kMaxDupExampleTags); ++i) {
            g.tags.push_back(kv.second[i].tag);
        }
        groups.push_back(std::move(g));
    }

    std::sort(groups.begin(), groups.end(), [](const GroupEntry& a, const GroupEntry& b) {
        return a.wasted > b.wasted;
    });

    const size_t cap = std::min<size_t>(groups.size(), static_cast<size_t>(limit_groups));
    out.reserve(cap);
    for (size_t i = 0; i < cap; ++i) {
        DuplicateStringGroupNative d{};
        d.value = groups[i].value;
        d.count = groups[i].count;
        d.wasted_bytes = groups[i].wasted;
        d.example_tags = std::move(groups[i].tags);
        out.push_back(std::move(d));
    }

    return out;
}

}  // namespace bytesight
