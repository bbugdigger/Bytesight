package com.bugdigger.core.analysis

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Extracts class metadata from raw `.class` bytes using ASM. Mirrors what
 * `BytesightAgentService.buildClassInfo` produces in the agent (which uses
 * Java reflection on a live `Class<?>`), but works without an attached JVM —
 * required for static-only sources (JAR / APK / saved project file).
 *
 * Returns module-local data classes; the mapping to `protocol.ClassInfo`
 * lives in `composeApp` so `core` stays free of a `protocol` dependency.
 */
class StaticHierarchyExtractor {

    fun extract(bytecode: ByteArray): StaticClassMetadata {
        val reader = ClassReader(bytecode)

        var name = ""
        var declaredSuper: String? = null
        var interfaces: List<String> = emptyList()
        var modifiers = 0
        val methods = mutableListOf<StaticMethodMetadata>()
        val fields = mutableListOf<StaticFieldMetadata>()

        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                clsName: String,
                signature: String?,
                superClsName: String?,
                interfaceNames: Array<out String>?,
            ) {
                name = clsName.replace('/', '.')
                modifiers = access
                declaredSuper = superClsName?.replace('/', '.')
                interfaces = interfaceNames?.map { it.replace('/', '.') } ?: emptyList()
            }

            override fun visitMethod(
                access: Int,
                methodName: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                methods.add(
                    StaticMethodMetadata(
                        name = methodName,
                        descriptor = descriptor,
                        returnType = Type.getReturnType(descriptor).className,
                        parameterTypes = Type.getArgumentTypes(descriptor).map { it.className },
                        modifiers = access,
                        isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0,
                        isBridge = (access and Opcodes.ACC_BRIDGE) != 0,
                    ),
                )
                return null
            }

            override fun visitField(
                access: Int,
                fieldName: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                fields.add(
                    StaticFieldMetadata(
                        name = fieldName,
                        descriptor = descriptor,
                        type = Type.getType(descriptor).className,
                        modifiers = access,
                        isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0,
                    ),
                )
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)

        // For java.lang.Object the class file's super is null. For everything
        // else ASM reports a non-null super; preserve null only for Object.
        val superName = when {
            declaredSuper != null -> declaredSuper
            name == "java.lang.Object" -> null
            else -> "java.lang.Object"
        }

        val packageName = name.substringBeforeLast('.', missingDelimiterValue = "")
        val simpleName = name.substringAfterLast('.')

        return StaticClassMetadata(
            name = name,
            packageName = packageName,
            simpleName = simpleName,
            superName = superName,
            interfaces = interfaces,
            modifiers = modifiers,
            isInterface = (modifiers and Opcodes.ACC_INTERFACE) != 0,
            isEnum = (modifiers and Opcodes.ACC_ENUM) != 0,
            isAnnotation = (modifiers and Opcodes.ACC_ANNOTATION) != 0,
            isSynthetic = (modifiers and Opcodes.ACC_SYNTHETIC) != 0,
            methods = methods,
            fields = fields,
        )
    }
}

data class StaticClassMetadata(
    val name: String,
    val packageName: String,
    val simpleName: String,
    val superName: String?,
    val interfaces: List<String>,
    val modifiers: Int,
    val isInterface: Boolean,
    val isEnum: Boolean,
    val isAnnotation: Boolean,
    val isSynthetic: Boolean,
    val methods: List<StaticMethodMetadata>,
    val fields: List<StaticFieldMetadata>,
)

data class StaticMethodMetadata(
    val name: String,
    val descriptor: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val modifiers: Int,
    val isSynthetic: Boolean,
    val isBridge: Boolean,
)

data class StaticFieldMetadata(
    val name: String,
    val descriptor: String,
    val type: String,
    val modifiers: Int,
    val isSynthetic: Boolean,
)
