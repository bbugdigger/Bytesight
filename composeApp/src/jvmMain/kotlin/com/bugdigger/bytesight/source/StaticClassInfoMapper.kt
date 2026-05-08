package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticClassMetadata
import com.bugdigger.core.analysis.StaticFieldMetadata
import com.bugdigger.core.analysis.StaticMethodMetadata
import com.bugdigger.protocol.ClassInfo
import com.bugdigger.protocol.FieldInfo
import com.bugdigger.protocol.MethodInfo

/**
 * Adapter from `core`'s pure-Kotlin static metadata types to the gRPC
 * [ClassInfo] proto. Lives in `composeApp` because `core` deliberately
 * doesn't depend on the proto module.
 */
object StaticClassInfoMapper {

    fun toClassInfo(md: StaticClassMetadata, classLoaderName: String): ClassInfo {
        val builder = ClassInfo.newBuilder()
            .setName(md.name)
            .setPackageName(md.packageName)
            .setSimpleName(md.simpleName)
            .setSuperclass(md.superName ?: "")
            .setModifiers(md.modifiers)
            .setIsInterface(md.isInterface)
            .setIsEnum(md.isEnum)
            .setIsAnnotation(md.isAnnotation)
            .setIsSynthetic(md.isSynthetic)
            .setLoadedAt(0L)
            .setClassLoader(classLoaderName)

        builder.addAllInterfaces(md.interfaces)
        md.methods.forEach { builder.addMethods(toMethodInfo(it)) }
        md.fields.forEach { builder.addFields(toFieldInfo(it)) }
        return builder.build()
    }

    private fun toMethodInfo(m: StaticMethodMetadata): MethodInfo =
        MethodInfo.newBuilder()
            .setName(m.name)
            .setSignature(m.descriptor)
            .setReturnType(m.returnType)
            .addAllParameterTypes(m.parameterTypes)
            .setModifiers(m.modifiers)
            .setIsSynthetic(m.isSynthetic)
            .setIsBridge(m.isBridge)
            .build()

    private fun toFieldInfo(f: StaticFieldMetadata): FieldInfo =
        FieldInfo.newBuilder()
            .setName(f.name)
            .setType(f.type)
            .setModifiers(f.modifiers)
            .setIsSynthetic(f.isSynthetic)
            .build()
}
