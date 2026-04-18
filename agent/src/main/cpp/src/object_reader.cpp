#include "bytesight_heap.h"

#include <jni.h>
#include <algorithm>
#include <cstring>
#include <sstream>

// Implements HeapContext::listInstances and HeapContext::getObject.
//
// These run on the gRPC thread and require a JNIEnv — not the jvmtiEnv of the
// original iteration. We recover the jobject for a tag via GetObjectsWithTags,
// then use JNI to read fields. All access is read-only; we never allocate or
// invoke Java code that could have side effects (no toString()).

namespace bytesight {

namespace {

constexpr int kMaxArrayPreview = 16;
constexpr int kMaxStringPreview = 80;
constexpr int kMaxObjectRefChildren = 64;  // outgoing edges reported per call

// Look up the jclass for a tag previously recorded in `class_name_by_tag`.
// Returns nullptr if the tag is unknown or the class is gone.
jobject get_object_for_tag(jvmtiEnv* jvmti, JNIEnv* env, jlong tag) {
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

// Fetches the tag attached to `obj` (0 if none).
jlong tag_for_object(jvmtiEnv* jvmti, jobject obj) {
    if (obj == nullptr) return 0;
    jlong tag = 0;
    if (jvmti->GetTag(obj, &tag) != JVMTI_ERROR_NONE) return 0;
    return tag;
}

bool is_object_signature(const char* sig) {
    return sig != nullptr && (sig[0] == 'L' || sig[0] == '[');
}

// Reads a primitive field into a FieldValueNative. Returns true on success.
bool read_primitive_field(JNIEnv* env, jobject obj, jfieldID fid, char type_char, FieldValueNative* out) {
    switch (type_char) {
        case 'Z': out->int_value = env->GetBooleanField(obj, fid) ? 1 : 0; out->kind = FieldValueNative::Kind::INT; return true;
        case 'B': out->int_value = env->GetByteField(obj, fid); out->kind = FieldValueNative::Kind::INT; return true;
        case 'C': out->int_value = env->GetCharField(obj, fid); out->kind = FieldValueNative::Kind::INT; return true;
        case 'S': out->int_value = env->GetShortField(obj, fid); out->kind = FieldValueNative::Kind::INT; return true;
        case 'I': out->int_value = env->GetIntField(obj, fid); out->kind = FieldValueNative::Kind::INT; return true;
        case 'J': out->int_value = env->GetLongField(obj, fid); out->kind = FieldValueNative::Kind::INT; return true;
        case 'F': out->double_value = env->GetFloatField(obj, fid); out->kind = FieldValueNative::Kind::DOUBLE; return true;
        case 'D': out->double_value = env->GetDoubleField(obj, fid); out->kind = FieldValueNative::Kind::DOUBLE; return true;
        default: return false;
    }
}

// Read the value of a java.lang.String into a UTF-8 std::string (best effort, truncated).
// Handles both Latin-1 and UTF-16 compact-string encodings by delegating to JNI's GetStringUTFChars.
std::string read_string_value(JNIEnv* env, jobject str_obj, int max_len) {
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
        s.append("…");
    }
    return s;
}

std::string short_preview_for(JNIEnv* env, jobject obj, const std::string& class_name, jlong tag) {
    if (obj == nullptr) return class_name + "@null";
    // Strings get their actual value (side-effect free).
    if (class_name == "java.lang.String") {
        return "\"" + read_string_value(env, obj, kMaxStringPreview) + "\"";
    }
    std::ostringstream oss;
    oss << class_name << "@" << tag;
    return oss.str();
}

// Append a pretty array summary (length + first N elements for primitive arrays).
void append_array_summary(JNIEnv* env, jobject arr, const std::string& element_sig, FieldValueNative* out) {
    if (arr == nullptr) {
        out->kind = FieldValueNative::Kind::NULLVAL;
        out->is_null = true;
        return;
    }
    jsize len = env->GetArrayLength(reinterpret_cast<jarray>(arr));
    std::ostringstream oss;
    oss << "length=" << len;
    if (!element_sig.empty() && element_sig[0] != 'L' && element_sig[0] != '[') {
        // Primitive element — show a short inline preview.
        oss << " [";
        const jsize preview_len = std::min<jsize>(len, kMaxArrayPreview);
        for (jsize i = 0; i < preview_len; ++i) {
            if (i > 0) oss << ", ";
            switch (element_sig[0]) {
                case 'Z': {
                    jboolean v = JNI_FALSE;
                    env->GetBooleanArrayRegion(reinterpret_cast<jbooleanArray>(arr), i, 1, &v);
                    oss << (v ? "true" : "false");
                    break;
                }
                case 'B': {
                    jbyte v = 0;
                    env->GetByteArrayRegion(reinterpret_cast<jbyteArray>(arr), i, 1, &v);
                    oss << static_cast<int>(v);
                    break;
                }
                case 'C': {
                    jchar v = 0;
                    env->GetCharArrayRegion(reinterpret_cast<jcharArray>(arr), i, 1, &v);
                    oss << static_cast<int>(v);
                    break;
                }
                case 'S': {
                    jshort v = 0;
                    env->GetShortArrayRegion(reinterpret_cast<jshortArray>(arr), i, 1, &v);
                    oss << v;
                    break;
                }
                case 'I': {
                    jint v = 0;
                    env->GetIntArrayRegion(reinterpret_cast<jintArray>(arr), i, 1, &v);
                    oss << v;
                    break;
                }
                case 'J': {
                    jlong v = 0;
                    env->GetLongArrayRegion(reinterpret_cast<jlongArray>(arr), i, 1, &v);
                    oss << v;
                    break;
                }
                case 'F': {
                    jfloat v = 0;
                    env->GetFloatArrayRegion(reinterpret_cast<jfloatArray>(arr), i, 1, &v);
                    oss << v;
                    break;
                }
                case 'D': {
                    jdouble v = 0;
                    env->GetDoubleArrayRegion(reinterpret_cast<jdoubleArray>(arr), i, 1, &v);
                    oss << v;
                    break;
                }
                default: break;
            }
        }
        if (len > preview_len) oss << ", … " << (len - preview_len) << " more";
        oss << "]";
    }
    out->kind = FieldValueNative::Kind::STRING;
    out->string_value = oss.str();
}

void read_one_field(
    JNIEnv* env,
    jvmtiEnv* jvmti,
    HeapSnapshot* snap,
    jobject obj,
    jclass owning_class,
    jfieldID fid,
    const char* name,
    const char* sig,
    bool is_static,
    ObjectDetailNative* out) {
    FieldValueNative fv{};
    fv.name = name != nullptr ? name : "";
    fv.declared_type = signature_to_java_name(sig != nullptr ? sig : "V");
    fv.is_static = is_static;

    if (sig == nullptr || sig[0] == '\0') {
        fv.kind = FieldValueNative::Kind::NULLVAL;
        fv.is_null = true;
        out->fields.push_back(std::move(fv));
        return;
    }

    const char type_char = sig[0];

    if (type_char != 'L' && type_char != '[') {
        // Primitive field
        if (is_static) {
            switch (type_char) {
                case 'Z': fv.int_value = env->GetStaticBooleanField(owning_class, fid) ? 1 : 0; fv.kind = FieldValueNative::Kind::INT; break;
                case 'B': fv.int_value = env->GetStaticByteField(owning_class, fid); fv.kind = FieldValueNative::Kind::INT; break;
                case 'C': fv.int_value = env->GetStaticCharField(owning_class, fid); fv.kind = FieldValueNative::Kind::INT; break;
                case 'S': fv.int_value = env->GetStaticShortField(owning_class, fid); fv.kind = FieldValueNative::Kind::INT; break;
                case 'I': fv.int_value = env->GetStaticIntField(owning_class, fid); fv.kind = FieldValueNative::Kind::INT; break;
                case 'J': fv.int_value = env->GetStaticLongField(owning_class, fid); fv.kind = FieldValueNative::Kind::INT; break;
                case 'F': fv.double_value = env->GetStaticFloatField(owning_class, fid); fv.kind = FieldValueNative::Kind::DOUBLE; break;
                case 'D': fv.double_value = env->GetStaticDoubleField(owning_class, fid); fv.kind = FieldValueNative::Kind::DOUBLE; break;
                default: fv.kind = FieldValueNative::Kind::NULLVAL; fv.is_null = true; break;
            }
        } else {
            if (!read_primitive_field(env, obj, fid, type_char, &fv)) {
                fv.kind = FieldValueNative::Kind::NULLVAL;
                fv.is_null = true;
            }
        }
        out->fields.push_back(std::move(fv));
        return;
    }

    // Object/array field
    jobject value = is_static
        ? env->GetStaticObjectField(owning_class, fid)
        : env->GetObjectField(obj, fid);

    if (env->ExceptionCheck()) env->ExceptionClear();

    if (value == nullptr) {
        fv.kind = FieldValueNative::Kind::NULLVAL;
        fv.is_null = true;
        out->fields.push_back(std::move(fv));
        return;
    }

    // Arrays — render as "length=N [elements…]" for primitives, "length=N" for refs.
    if (type_char == '[') {
        const std::string elem_sig(sig + 1);  // skip '['
        append_array_summary(env, value, elem_sig, &fv);
        // For object arrays, also attach a ref edge so the user can navigate into the array.
        if (!elem_sig.empty() && (elem_sig[0] == 'L' || elem_sig[0] == '[')) {
            const jlong target_tag = tag_for_object(jvmti, value);
            if (target_tag != 0) {
                fv.kind = FieldValueNative::Kind::REF;
                fv.ref_tag = target_tag;
                // Resolve the array object's class name from the snapshot map.
                auto it = snap->tagged.find(target_tag);
                const std::string target_class_name = it != snap->tagged.end()
                    ? it->second.class_name
                    : fv.declared_type;
                out->outgoing_refs.push_back({fv.name, target_tag, target_class_name});
            }
        }
        env->DeleteLocalRef(value);
        out->fields.push_back(std::move(fv));
        return;
    }

    // Regular object field
    const std::string declared_class = signature_to_java_name(sig);
    if (declared_class == "java.lang.String") {
        fv.kind = FieldValueNative::Kind::STRING;
        fv.string_value = read_string_value(env, value, kMaxStringPreview);
    } else {
        fv.kind = FieldValueNative::Kind::REF;
        const jlong target_tag = tag_for_object(jvmti, value);
        fv.ref_tag = target_tag;
        if (target_tag != 0) {
            auto it = snap->tagged.find(target_tag);
            const std::string target_class_name = it != snap->tagged.end()
                ? it->second.class_name
                : declared_class;
            out->outgoing_refs.push_back({fv.name, target_tag, target_class_name});
        }
    }
    env->DeleteLocalRef(value);
    out->fields.push_back(std::move(fv));
}

void read_fields_of_class(
    JNIEnv* env,
    jvmtiEnv* jvmti,
    HeapSnapshot* snap,
    jobject obj,
    jclass klass,
    ObjectDetailNative* out) {
    jint count = 0;
    jfieldID* fields = nullptr;
    if (jvmti->GetClassFields(klass, &count, &fields) != JVMTI_ERROR_NONE || fields == nullptr) {
        return;
    }

    for (jint i = 0; i < count; ++i) {
        char* name = nullptr;
        char* sig = nullptr;
        jint mods = 0;
        if (jvmti->GetFieldName(klass, fields[i], &name, &sig, nullptr) != JVMTI_ERROR_NONE) {
            continue;
        }
        if (jvmti->GetFieldModifiers(klass, fields[i], &mods) != JVMTI_ERROR_NONE) {
            if (name) jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
            if (sig) jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
            continue;
        }

        constexpr jint ACC_STATIC = 0x0008;
        const bool is_static = (mods & ACC_STATIC) != 0;

        read_one_field(env, jvmti, snap, obj, klass, fields[i], name, sig, is_static, out);

        if (name) jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
        if (sig) jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
    }

    jvmti->Deallocate(reinterpret_cast<unsigned char*>(fields));
}

}  // namespace

std::vector<InstanceSummaryNative> HeapContext::listInstances(
    JNIEnv* env,
    int64_t snapshot_id,
    const std::string& class_name,
    int32_t limit) {
    std::vector<InstanceSummaryNative> out;

    HeapSnapshot* snap = findSnapshot(snapshot_id);
    if (snap == nullptr) return out;

    auto it = snap->instances_by_class.find(class_name);
    if (it == snap->instances_by_class.end()) return out;

    const std::vector<int64_t>& tags = it->second;
    const size_t cap = (limit > 0) ? std::min<size_t>(tags.size(), static_cast<size_t>(limit)) : tags.size();
    out.reserve(cap);

    for (size_t i = 0; i < cap; ++i) {
        const int64_t tag = tags[i];
        auto mit = snap->tagged.find(tag);
        if (mit == snap->tagged.end()) continue;

        InstanceSummaryNative s{};
        s.tag = tag;
        s.class_name = mit->second.class_name;
        s.shallow_bytes = mit->second.shallow_size;

        jobject obj = get_object_for_tag(jvmti_, env, tag);
        s.preview = short_preview_for(env, obj, s.class_name, tag);
        if (obj != nullptr) env->DeleteLocalRef(obj);

        out.push_back(std::move(s));
    }

    return out;
}

ObjectDetailNative HeapContext::getObject(JNIEnv* env, int64_t snapshot_id, int64_t tag) {
    ObjectDetailNative out{};
    out.tag = tag;

    HeapSnapshot* snap = findSnapshot(snapshot_id);
    if (snap == nullptr) {
        out.error = "Snapshot not found";
        return out;
    }

    auto mit = snap->tagged.find(tag);
    if (mit == snap->tagged.end()) {
        out.error = "Tag not in snapshot";
        return out;
    }

    out.class_name = mit->second.class_name;
    out.shallow_bytes = mit->second.shallow_size;

    jobject obj = get_object_for_tag(jvmti_, env, tag);
    if (obj == nullptr) {
        out.error = "Object no longer reachable";
        return out;
    }

    jclass klass = env->GetObjectClass(obj);
    if (klass == nullptr) {
        env->DeleteLocalRef(obj);
        out.error = "GetObjectClass returned null";
        return out;
    }

    // Walk the class and every superclass, collecting fields.
    jclass cur = klass;
    bool first = true;
    while (cur != nullptr) {
        read_fields_of_class(env, jvmti_, snap, obj, cur, &out);
        jclass super_cls = env->GetSuperclass(cur);
        if (!first) env->DeleteLocalRef(cur);
        first = false;
        cur = super_cls;
        // Stop at java.lang.Object or when we've hit a null superclass.
        if (cur == nullptr) break;
        char* cur_sig = nullptr;
        if (jvmti_->GetClassSignature(cur, &cur_sig, nullptr) == JVMTI_ERROR_NONE && cur_sig != nullptr) {
            const bool is_object = std::strcmp(cur_sig, "Ljava/lang/Object;") == 0;
            jvmti_->Deallocate(reinterpret_cast<unsigned char*>(cur_sig));
            if (is_object) {
                env->DeleteLocalRef(cur);
                break;
            }
        }
    }

    // For arrays, synthesise ref edges for the first N object elements so the user can navigate.
    if (!out.class_name.empty() && out.class_name.back() == ']') {
        // it's an array class (e.g. "java.lang.String[]" or "int[]")
        const bool is_object_array = out.class_name.find("[]") != std::string::npos
            && !(out.class_name.rfind("int[", 0) == 0)
            // Quick heuristic; we rely on GetArrayLength + GetObjectArrayElement when it's an Object[].
            ;
        (void)is_object_array;
        if (env->IsInstanceOf(obj, env->FindClass("[Ljava/lang/Object;"))) {
            jobjectArray oarr = reinterpret_cast<jobjectArray>(obj);
            const jsize len = env->GetArrayLength(oarr);
            const jsize limit = std::min<jsize>(len, kMaxObjectRefChildren);
            for (jsize i = 0; i < limit; ++i) {
                jobject el = env->GetObjectArrayElement(oarr, i);
                if (env->ExceptionCheck()) { env->ExceptionClear(); continue; }
                if (el == nullptr) continue;
                const jlong target_tag = tag_for_object(jvmti_, el);
                if (target_tag != 0) {
                    auto tit = snap->tagged.find(target_tag);
                    std::ostringstream oss; oss << "[" << i << "]";
                    out.outgoing_refs.push_back({
                        oss.str(),
                        target_tag,
                        tit != snap->tagged.end() ? tit->second.class_name : std::string("java.lang.Object")
                    });
                }
                env->DeleteLocalRef(el);
            }
        }
    }

    if (klass != nullptr) env->DeleteLocalRef(klass);
    env->DeleteLocalRef(obj);
    out.found = true;
    return out;
}

}  // namespace bytesight
