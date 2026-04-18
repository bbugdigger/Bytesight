#include "bytesight_heap.h"

#include <jni.h>
#include <algorithm>
#include <cstring>

// JNI entry points are declared with the class name that Java uses for binding:
//   com.bugdigger.agent.heap.HeapInspector -> Java_com_bugdigger_agent_heap_HeapInspector_*
//
// The Java side calls these with already-prepared arguments. We keep this layer
// thin and push logic into the bytesight:: namespace.

namespace {

jobject build_snapshot_info(
    JNIEnv* env,
    const bytesight::HeapSnapshot& snap,
    bool available,
    const std::string& error) {
    jclass cls = env->FindClass("com/bugdigger/agent/heap/HeapInspector$NativeSnapshotInfo");
    if (cls == nullptr) return nullptr;

    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJJZLjava/lang/String;)V");
    if (ctor == nullptr) return nullptr;

    jstring err_str = env->NewStringUTF(error.c_str());
    return env->NewObject(
        cls, ctor,
        static_cast<jlong>(snap.snapshot_id),
        static_cast<jlong>(snap.object_count),
        static_cast<jlong>(snap.total_shallow_bytes),
        static_cast<jlong>(snap.captured_at_millis),
        static_cast<jboolean>(available),
        err_str);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_bugdigger_agent_heap_HeapInspector_nativeInit(JNIEnv* env, jclass /*cls*/) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return JNI_FALSE;
    return bytesight::HeapContext::instance().init(vm) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_bugdigger_agent_heap_HeapInspector_nativeIsAvailable(JNIEnv* /*env*/, jclass /*cls*/) {
    return bytesight::HeapContext::instance().available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_bugdigger_agent_heap_HeapInspector_nativeCaptureSnapshot(JNIEnv* env, jclass /*cls*/) {
    std::string err;
    bytesight::HeapSnapshot* snap = bytesight::HeapContext::instance().captureSnapshot(&err);
    if (snap == nullptr) {
        bytesight::HeapSnapshot empty{};
        return build_snapshot_info(env, empty, /*available=*/false, err);
    }
    return build_snapshot_info(env, *snap, /*available=*/true, "");
}

// Returns a 2D Object[][] where each row is {String className, Long count, Long bytes}.
// Empty array if the snapshot is unknown.
JNIEXPORT jobjectArray JNICALL
Java_com_bugdigger_agent_heap_HeapInspector_nativeGetClassHistogram(
    JNIEnv* env, jclass /*cls*/, jlong snapshot_id, jstring filter) {
    bytesight::HeapSnapshot* snap = bytesight::HeapContext::instance().findSnapshot(snapshot_id);

    jclass object_cls = env->FindClass("java/lang/Object");
    jclass object_array_cls = env->FindClass("[Ljava/lang/Object;");
    jclass long_cls = env->FindClass("java/lang/Long");
    jmethodID long_ctor = env->GetMethodID(long_cls, "<init>", "(J)V");

    if (snap == nullptr) {
        return env->NewObjectArray(0, object_array_cls, nullptr);
    }

    std::string needle;
    if (filter != nullptr) {
        const char* s = env->GetStringUTFChars(filter, nullptr);
        if (s != nullptr) {
            needle = s;
            env->ReleaseStringUTFChars(filter, s);
        }
    }

    // Filter + collect into a vector so we can build a Java array of known length.
    std::vector<std::pair<std::string, bytesight::ClassHistogramBucket>> rows;
    rows.reserve(snap->histogram.size());
    for (const auto& kv : snap->histogram) {
        if (needle.empty() || kv.first.find(needle) != std::string::npos) {
            rows.emplace_back(kv.first, kv.second);
        }
    }

    const jsize n = static_cast<jsize>(rows.size());
    jobjectArray out = env->NewObjectArray(n, object_array_cls, nullptr);
    for (jsize i = 0; i < n; ++i) {
        jobjectArray row = env->NewObjectArray(3, object_cls, nullptr);

        jstring name = env->NewStringUTF(rows[i].first.c_str());
        jobject count = env->NewObject(long_cls, long_ctor, static_cast<jlong>(rows[i].second.instance_count));
        jobject bytes = env->NewObject(long_cls, long_ctor, static_cast<jlong>(rows[i].second.shallow_bytes));

        env->SetObjectArrayElement(row, 0, name);
        env->SetObjectArrayElement(row, 1, count);
        env->SetObjectArrayElement(row, 2, bytes);

        env->SetObjectArrayElement(out, i, row);

        env->DeleteLocalRef(name);
        env->DeleteLocalRef(count);
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(row);
    }

    return out;
}

// Returns a 2D Object[][] where each row is {Long tag, String className, Long shallowBytes, String preview}.
JNIEXPORT jobjectArray JNICALL
Java_com_bugdigger_agent_heap_HeapInspector_nativeListInstances(
    JNIEnv* env, jclass /*cls*/, jlong snapshot_id, jstring class_name, jint limit) {
    jclass object_cls = env->FindClass("java/lang/Object");
    jclass object_array_cls = env->FindClass("[Ljava/lang/Object;");
    jclass long_cls = env->FindClass("java/lang/Long");
    jmethodID long_ctor = env->GetMethodID(long_cls, "<init>", "(J)V");

    std::string name;
    if (class_name != nullptr) {
        const char* s = env->GetStringUTFChars(class_name, nullptr);
        if (s != nullptr) { name = s; env->ReleaseStringUTFChars(class_name, s); }
    }

    auto results = bytesight::HeapContext::instance().listInstances(env, snapshot_id, name, limit);
    const jsize n = static_cast<jsize>(results.size());
    jobjectArray out = env->NewObjectArray(n, object_array_cls, nullptr);

    for (jsize i = 0; i < n; ++i) {
        const auto& inst = results[i];
        jobjectArray row = env->NewObjectArray(4, object_cls, nullptr);

        jobject tag_obj = env->NewObject(long_cls, long_ctor, static_cast<jlong>(inst.tag));
        jstring cls_str = env->NewStringUTF(inst.class_name.c_str());
        jobject bytes_obj = env->NewObject(long_cls, long_ctor, static_cast<jlong>(inst.shallow_bytes));
        jstring preview_str = env->NewStringUTF(inst.preview.c_str());

        env->SetObjectArrayElement(row, 0, tag_obj);
        env->SetObjectArrayElement(row, 1, cls_str);
        env->SetObjectArrayElement(row, 2, bytes_obj);
        env->SetObjectArrayElement(row, 3, preview_str);

        env->SetObjectArrayElement(out, i, row);

        env->DeleteLocalRef(tag_obj);
        env->DeleteLocalRef(cls_str);
        env->DeleteLocalRef(bytes_obj);
        env->DeleteLocalRef(preview_str);
        env->DeleteLocalRef(row);
    }

    return out;
}

// Returns a NativeObjectDetail jobject with fields and outgoing refs as Object[][] arrays.
JNIEXPORT jobject JNICALL
Java_com_bugdigger_agent_heap_HeapInspector_nativeGetObject(
    JNIEnv* env, jclass /*cls*/, jlong snapshot_id, jlong tag) {

    auto detail = bytesight::HeapContext::instance().getObject(env, snapshot_id, tag);

    jclass detail_cls = env->FindClass("com/bugdigger/agent/heap/HeapInspector$NativeObjectDetail");
    if (detail_cls == nullptr) return nullptr;

    // Build fields Object[][] — each row: {String name, String declaredType, Integer kind, Long intVal, Double doubleVal, String stringVal, Long refTag, Boolean isStatic, Boolean isNull}
    jclass object_cls = env->FindClass("java/lang/Object");
    jclass object_array_cls = env->FindClass("[Ljava/lang/Object;");
    jclass long_cls = env->FindClass("java/lang/Long");
    jclass int_cls = env->FindClass("java/lang/Integer");
    jclass double_cls = env->FindClass("java/lang/Double");
    jclass bool_cls = env->FindClass("java/lang/Boolean");
    jmethodID long_ctor = env->GetMethodID(long_cls, "<init>", "(J)V");
    jmethodID int_ctor = env->GetMethodID(int_cls, "<init>", "(I)V");
    jmethodID double_ctor = env->GetMethodID(double_cls, "<init>", "(D)V");
    jmethodID bool_ctor = env->GetMethodID(bool_cls, "<init>", "(Z)V");

    // Fields array
    const jsize field_count = static_cast<jsize>(detail.fields.size());
    jobjectArray fields_arr = env->NewObjectArray(field_count, object_array_cls, nullptr);
    for (jsize i = 0; i < field_count; ++i) {
        const auto& f = detail.fields[i];
        jobjectArray row = env->NewObjectArray(9, object_cls, nullptr);

        jstring name_str = env->NewStringUTF(f.name.c_str());
        jstring type_str = env->NewStringUTF(f.declared_type.c_str());
        int kind_val = 0;
        switch (f.kind) {
            case bytesight::FieldValueNative::Kind::INT: kind_val = 1; break;
            case bytesight::FieldValueNative::Kind::DOUBLE: kind_val = 2; break;
            case bytesight::FieldValueNative::Kind::STRING: kind_val = 3; break;
            case bytesight::FieldValueNative::Kind::REF: kind_val = 4; break;
            case bytesight::FieldValueNative::Kind::NULLVAL: kind_val = 5; break;
        }
        jobject kind_obj = env->NewObject(int_cls, int_ctor, kind_val);
        jobject int_val_obj = env->NewObject(long_cls, long_ctor, static_cast<jlong>(f.int_value));
        jobject double_val_obj = env->NewObject(double_cls, double_ctor, f.double_value);
        jstring string_val = env->NewStringUTF(f.string_value.c_str());
        jobject ref_tag_obj = env->NewObject(long_cls, long_ctor, static_cast<jlong>(f.ref_tag));
        jobject is_static_obj = env->NewObject(bool_cls, bool_ctor, static_cast<jboolean>(f.is_static ? JNI_TRUE : JNI_FALSE));
        jobject is_null_obj = env->NewObject(bool_cls, bool_ctor, static_cast<jboolean>(f.is_null ? JNI_TRUE : JNI_FALSE));

        env->SetObjectArrayElement(row, 0, name_str);
        env->SetObjectArrayElement(row, 1, type_str);
        env->SetObjectArrayElement(row, 2, kind_obj);
        env->SetObjectArrayElement(row, 3, int_val_obj);
        env->SetObjectArrayElement(row, 4, double_val_obj);
        env->SetObjectArrayElement(row, 5, string_val);
        env->SetObjectArrayElement(row, 6, ref_tag_obj);
        env->SetObjectArrayElement(row, 7, is_static_obj);
        env->SetObjectArrayElement(row, 8, is_null_obj);

        env->SetObjectArrayElement(fields_arr, i, row);

        env->DeleteLocalRef(name_str); env->DeleteLocalRef(type_str); env->DeleteLocalRef(kind_obj);
        env->DeleteLocalRef(int_val_obj); env->DeleteLocalRef(double_val_obj); env->DeleteLocalRef(string_val);
        env->DeleteLocalRef(ref_tag_obj); env->DeleteLocalRef(is_static_obj); env->DeleteLocalRef(is_null_obj);
        env->DeleteLocalRef(row);
    }

    // Outgoing refs array — each row: {String fieldName, Long targetTag, String targetClassName}
    const jsize ref_count = static_cast<jsize>(detail.outgoing_refs.size());
    jobjectArray refs_arr = env->NewObjectArray(ref_count, object_array_cls, nullptr);
    for (jsize i = 0; i < ref_count; ++i) {
        const auto& r = detail.outgoing_refs[i];
        jobjectArray row = env->NewObjectArray(3, object_cls, nullptr);

        jstring field_name = env->NewStringUTF(r.field_name.c_str());
        jobject target_tag = env->NewObject(long_cls, long_ctor, static_cast<jlong>(r.target_tag));
        jstring target_class = env->NewStringUTF(r.target_class_name.c_str());

        env->SetObjectArrayElement(row, 0, field_name);
        env->SetObjectArrayElement(row, 1, target_tag);
        env->SetObjectArrayElement(row, 2, target_class);

        env->SetObjectArrayElement(refs_arr, i, row);

        env->DeleteLocalRef(field_name); env->DeleteLocalRef(target_tag); env->DeleteLocalRef(target_class);
        env->DeleteLocalRef(row);
    }

    // Construct NativeObjectDetail(tag, className, shallowBytes, found, error, fields, refs)
    jmethodID detail_ctor = env->GetMethodID(detail_cls, "<init>",
        "(JLjava/lang/String;JZLjava/lang/String;[[Ljava/lang/Object;[[Ljava/lang/Object;)V");
    if (detail_ctor == nullptr) return nullptr;

    jstring class_name_str = env->NewStringUTF(detail.class_name.c_str());
    jstring error_str = env->NewStringUTF(detail.error.c_str());

    jobject result = env->NewObject(detail_cls, detail_ctor,
        static_cast<jlong>(detail.tag),
        class_name_str,
        static_cast<jlong>(detail.shallow_bytes),
        static_cast<jboolean>(detail.found ? JNI_TRUE : JNI_FALSE),
        error_str,
        fields_arr,
        refs_arr);

    env->DeleteLocalRef(class_name_str);
    env->DeleteLocalRef(error_str);
    env->DeleteLocalRef(fields_arr);
    env->DeleteLocalRef(refs_arr);

    return result;
}

// ========== Phase 3: Search & Duplicates ==========

// Returns Object[][] where each row is {Long tag, String className, String matchedField, String matchedValue}.
JNIEXPORT jobjectArray JNICALL
Java_com_bugdigger_agent_heap_HeapInspector_nativeSearchValues(
    JNIEnv* env, jclass /*cls*/, jlong snapshot_id,
    jstring string_contains, jstring field_class_name,
    jstring field_name, jstring field_value, jint limit) {

    jclass object_cls = env->FindClass("java/lang/Object");
    jclass object_array_cls = env->FindClass("[Ljava/lang/Object;");
    jclass long_cls = env->FindClass("java/lang/Long");
    jmethodID long_ctor = env->GetMethodID(long_cls, "<init>", "(J)V");

    auto to_std = [&](jstring js) -> std::string {
        if (js == nullptr) return "";
        const char* s = env->GetStringUTFChars(js, nullptr);
        if (s == nullptr) return "";
        std::string r(s);
        env->ReleaseStringUTFChars(js, s);
        return r;
    };

    std::string str_contains = to_std(string_contains);
    std::string cls_name = to_std(field_class_name);
    std::string f_name = to_std(field_name);
    std::string f_value = to_std(field_value);

    std::vector<bytesight::ValueMatchNative> results;
    if (!str_contains.empty()) {
        results = bytesight::HeapContext::instance().searchStringContains(env, snapshot_id, str_contains, limit);
    } else if (!cls_name.empty() && !f_name.empty()) {
        results = bytesight::HeapContext::instance().searchFieldEquals(env, snapshot_id, cls_name, f_name, f_value, limit);
    }

    const jsize n = static_cast<jsize>(results.size());
    jobjectArray out = env->NewObjectArray(n, object_array_cls, nullptr);

    for (jsize i = 0; i < n; ++i) {
        const auto& m = results[i];
        jobjectArray row = env->NewObjectArray(4, object_cls, nullptr);

        jobject tag_obj = env->NewObject(long_cls, long_ctor, static_cast<jlong>(m.tag));
        jstring cls_str = env->NewStringUTF(m.class_name.c_str());
        jstring field_str = env->NewStringUTF(m.matched_field.c_str());
        jstring value_str = env->NewStringUTF(m.matched_value.c_str());

        env->SetObjectArrayElement(row, 0, tag_obj);
        env->SetObjectArrayElement(row, 1, cls_str);
        env->SetObjectArrayElement(row, 2, field_str);
        env->SetObjectArrayElement(row, 3, value_str);

        env->SetObjectArrayElement(out, i, row);

        env->DeleteLocalRef(tag_obj); env->DeleteLocalRef(cls_str);
        env->DeleteLocalRef(field_str); env->DeleteLocalRef(value_str);
        env->DeleteLocalRef(row);
    }

    return out;
}

// Returns Object[][] where each row is {String value, Integer count, Long wastedBytes, Long[] exampleTags}.
JNIEXPORT jobjectArray JNICALL
Java_com_bugdigger_agent_heap_HeapInspector_nativeFindDuplicateStrings(
    JNIEnv* env, jclass /*cls*/, jlong snapshot_id,
    jint min_count, jint min_length, jint limit_groups) {

    jclass object_cls = env->FindClass("java/lang/Object");
    jclass object_array_cls = env->FindClass("[Ljava/lang/Object;");
    jclass int_cls = env->FindClass("java/lang/Integer");
    jclass long_cls = env->FindClass("java/lang/Long");
    jmethodID int_ctor = env->GetMethodID(int_cls, "<init>", "(I)V");
    jmethodID long_ctor = env->GetMethodID(long_cls, "<init>", "(J)V");

    auto groups = bytesight::HeapContext::instance().findDuplicateStrings(
        env, snapshot_id, min_count, min_length, limit_groups);

    const jsize n = static_cast<jsize>(groups.size());
    jobjectArray out = env->NewObjectArray(n, object_array_cls, nullptr);

    for (jsize i = 0; i < n; ++i) {
        const auto& g = groups[i];
        jobjectArray row = env->NewObjectArray(4, object_cls, nullptr);

        jstring val_str = env->NewStringUTF(g.value.c_str());
        jobject count_obj = env->NewObject(int_cls, int_ctor, static_cast<jint>(g.count));
        jobject wasted_obj = env->NewObject(long_cls, long_ctor, static_cast<jlong>(g.wasted_bytes));

        // Build Long[] for example tags
        const jsize tag_count = static_cast<jsize>(g.example_tags.size());
        jobjectArray tags_arr = env->NewObjectArray(tag_count, long_cls, nullptr);
        for (jsize t = 0; t < tag_count; ++t) {
            jobject tag_obj = env->NewObject(long_cls, long_ctor, static_cast<jlong>(g.example_tags[t]));
            env->SetObjectArrayElement(tags_arr, t, tag_obj);
            env->DeleteLocalRef(tag_obj);
        }

        env->SetObjectArrayElement(row, 0, val_str);
        env->SetObjectArrayElement(row, 1, count_obj);
        env->SetObjectArrayElement(row, 2, wasted_obj);
        env->SetObjectArrayElement(row, 3, tags_arr);

        env->SetObjectArrayElement(out, i, row);

        env->DeleteLocalRef(val_str); env->DeleteLocalRef(count_obj);
        env->DeleteLocalRef(wasted_obj); env->DeleteLocalRef(tags_arr);
        env->DeleteLocalRef(row);
    }

    return out;
}

}  // extern "C"
