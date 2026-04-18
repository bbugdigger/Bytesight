#include "bytesight_heap.h"

namespace bytesight {

// Converts a JVMTI class signature to the standard Java name.
//   "Ljava/lang/String;"  -> "java.lang.String"
//   "[Ljava/lang/Object;" -> "java.lang.Object[]"
//   "[[I"                 -> "int[][]"
//   "I"                   -> "int"
std::string signature_to_java_name(const char* signature) {
    if (signature == nullptr || *signature == '\0') return "<unknown>";

    int array_dims = 0;
    const char* p = signature;
    while (*p == '[') {
        ++array_dims;
        ++p;
    }

    std::string base;
    switch (*p) {
        case 'B': base = "byte"; break;
        case 'C': base = "char"; break;
        case 'D': base = "double"; break;
        case 'F': base = "float"; break;
        case 'I': base = "int"; break;
        case 'J': base = "long"; break;
        case 'S': base = "short"; break;
        case 'Z': base = "boolean"; break;
        case 'V': base = "void"; break;
        case 'L': {
            // "Lfoo/bar/Baz;"
            ++p;  // skip 'L'
            while (*p != ';' && *p != '\0') {
                base.push_back(*p == '/' ? '.' : *p);
                ++p;
            }
            break;
        }
        default:
            base = signature;
            break;
    }

    for (int i = 0; i < array_dims; ++i) base += "[]";
    return base;
}

}  // namespace bytesight
