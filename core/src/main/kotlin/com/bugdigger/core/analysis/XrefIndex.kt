package com.bugdigger.core.analysis

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Cross-reference index over a project's bytecode. Built by walking every
 * class once with ASM and recording, for each symbol, every place it is
 * referenced from. Designed to be queried by the IDA-style xrefs popup
 * (press X on a method → list of callers).
 *
 * Two maps:
 * - [methodCallers] — `class#name+desc` (matches `RenameStore.methodKey`
 *   shape) → list of call sites.
 * - [classUsers]   — class FQN → list of references (NEW, CHECKCAST,
 *   INSTANCEOF, field types, parameter types, return types,
 *   superclass/interface declarations, LDC class literals).
 *
 * The keys are deliberately chosen to be the same shape Bytesight already
 * uses elsewhere — `RenameStore.methodKey(class, name, desc)` for methods,
 * raw FQN for classes — so callers don't need to translate.
 */
data class XrefIndex(
    val methodCallers: Map<String, List<XrefSite>>,
    val classUsers: Map<String, List<XrefSite>>,
) {
    companion object {
        val EMPTY = XrefIndex(emptyMap(), emptyMap())
    }
}

/**
 * One reference: where it lives in the project. The dialog renders these
 * as rows; clicking a row navigates the Inspector to
 * [callerClassFqn] + [callerMethodName] (using [callerMethodDescriptor]
 * for overload disambiguation).
 *
 * For class-level references that aren't inside any method (super-class,
 * interfaces, field types declared at the class level), [callerMethodName]
 * and [callerMethodDescriptor] are empty and [instructionOffset] is `-1`.
 */
data class XrefSite(
    val callerClassFqn: String,
    val callerMethodName: String,
    val callerMethodDescriptor: String,
    val instructionOffset: Int,
    val category: XrefCategory,
)

enum class XrefCategory {
    INVOKE_VIRTUAL, INVOKE_STATIC, INVOKE_SPECIAL, INVOKE_INTERFACE, INVOKE_DYNAMIC,
    NEW, INSTANCEOF, CHECKCAST, MULTI_ANEW_ARRAY, ANEW_ARRAY,
    FIELD_ACCESS,
    FIELD_TYPE, PARAM_TYPE, RETURN_TYPE, SUPERCLASS, INTERFACE, LDC_TYPE,
}

/**
 * Builds a [XrefIndex] over a project's bytecode.
 *
 * Implementation note: a single ASM pass per class. For each class we
 * extract:
 * - Class-level references — super, interfaces, field types, method
 *   signature types — all contributing to [XrefIndex.classUsers].
 * - Per-method body references — INVOKE/NEW/CHECKCAST/etc. — contributing
 *   to both maps.
 *
 * The [excludedClassPrefixes] filter trims noise from
 * [XrefIndex.classUsers]: target apps almost always reference
 * `java.lang.String`/`java.util.List`/etc., and listing every site of
 * those would drown out the interesting refs. The filter is applied to
 * the **target** class of each reference; the **caller** is always
 * recorded as-is. Methods are not filtered (the user's question
 * "who calls this method" is unambiguous).
 */
class XrefIndexer(
    private val excludedClassPrefixes: List<String> = DEFAULT_EXCLUDED_PREFIXES,
) {

    fun build(classes: Map<String, ByteArray>): XrefIndex {
        val methodCallers = HashMap<String, MutableList<XrefSite>>()
        val classUsers = HashMap<String, MutableList<XrefSite>>()

        for ((className, bytes) in classes) {
            indexClass(className, bytes, methodCallers, classUsers)
        }

        return XrefIndex(
            methodCallers = methodCallers,
            classUsers = classUsers,
        )
    }

    private fun indexClass(
        className: String,
        bytecode: ByteArray,
        methodCallers: MutableMap<String, MutableList<XrefSite>>,
        classUsers: MutableMap<String, MutableList<XrefSite>>,
    ) {
        val reader = ClassReader(bytecode)
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                superName?.let {
                    addClassUser(classUsers, it, className, "", "", -1, XrefCategory.SUPERCLASS)
                }
                interfaces?.forEach { iface ->
                    addClassUser(classUsers, iface, className, "", "", -1, XrefCategory.INTERFACE)
                }
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                addTypeUsersForDescriptor(
                    classUsers, descriptor, className, "", "", -1, XrefCategory.FIELD_TYPE,
                )
                return null
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                // Class-level: parameter and return types of the method declaration.
                val argTypes = Type.getArgumentTypes(descriptor)
                for (t in argTypes) {
                    addTypeUsersForDescriptor(
                        classUsers, t.descriptor, className, name, descriptor, -1,
                        XrefCategory.PARAM_TYPE,
                    )
                }
                val retType = Type.getReturnType(descriptor)
                addTypeUsersForDescriptor(
                    classUsers, retType.descriptor, className, name, descriptor, -1,
                    XrefCategory.RETURN_TYPE,
                )

                return BodyVisitor(
                    callerClass = className,
                    callerMethod = name,
                    callerMethodDescriptor = descriptor,
                    methodCallers = methodCallers,
                    classUsers = classUsers,
                )
            }
        }, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
    }

    private inner class BodyVisitor(
        private val callerClass: String,
        private val callerMethod: String,
        private val callerMethodDescriptor: String,
        private val methodCallers: MutableMap<String, MutableList<XrefSite>>,
        private val classUsers: MutableMap<String, MutableList<XrefSite>>,
    ) : MethodVisitor(Opcodes.ASM9) {

        // We don't need real bytecode offsets to navigate — the dialog only
        // shows rows. A monotonic counter per method is enough to disambiguate
        // multiple sites in the same method when we later highlight them.
        private var instructionCounter = 0

        override fun visitInsn(opcode: Int) { instructionCounter++ }
        override fun visitIntInsn(opcode: Int, operand: Int) { instructionCounter++ }
        override fun visitVarInsn(opcode: Int, varIndex: Int) { instructionCounter++ }
        override fun visitJumpInsn(opcode: Int, label: Label) { instructionCounter++ }
        override fun visitIincInsn(varIndex: Int, increment: Int) { instructionCounter++ }
        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) { instructionCounter++ }
        override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) { instructionCounter++ }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            val ownerFqn = owner.replace('/', '.')
            val targetKey = "$ownerFqn#$name$descriptor"
            val site = XrefSite(
                callerClassFqn = callerClass.replace('/', '.'),
                callerMethodName = callerMethod,
                callerMethodDescriptor = callerMethodDescriptor,
                instructionOffset = instructionCounter,
                category = invokeCategory(opcode),
            )
            methodCallers.getOrPut(targetKey) { mutableListOf() }.add(site)
            // Owner class is also a class user (methods are invoked on classes).
            addClassUser(
                classUsers, owner, callerClass, callerMethod, callerMethodDescriptor,
                instructionCounter, invokeCategory(opcode),
            )
            instructionCounter++
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bsm: Handle,
            vararg bsmArgs: Any?,
        ) {
            // Indy isn't a method invocation we can attribute to a target;
            // just track type users from the descriptor for completeness.
            val argTypes = Type.getArgumentTypes(descriptor)
            for (t in argTypes) {
                addTypeUsersForDescriptor(
                    classUsers, t.descriptor, callerClass, callerMethod, callerMethodDescriptor,
                    instructionCounter, XrefCategory.PARAM_TYPE,
                )
            }
            instructionCounter++
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            val cat = when (opcode) {
                Opcodes.NEW -> XrefCategory.NEW
                Opcodes.CHECKCAST -> XrefCategory.CHECKCAST
                Opcodes.INSTANCEOF -> XrefCategory.INSTANCEOF
                Opcodes.ANEWARRAY -> XrefCategory.ANEW_ARRAY
                else -> XrefCategory.LDC_TYPE
            }
            addClassUser(
                classUsers, type, callerClass, callerMethod, callerMethodDescriptor,
                instructionCounter, cat,
            )
            instructionCounter++
        }

        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
            addTypeUsersForDescriptor(
                classUsers, descriptor, callerClass, callerMethod, callerMethodDescriptor,
                instructionCounter, XrefCategory.MULTI_ANEW_ARRAY,
            )
            instructionCounter++
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            // Owner class is a user; the field type is a user too.
            addClassUser(
                classUsers, owner, callerClass, callerMethod, callerMethodDescriptor,
                instructionCounter, XrefCategory.FIELD_ACCESS,
            )
            addTypeUsersForDescriptor(
                classUsers, descriptor, callerClass, callerMethod, callerMethodDescriptor,
                instructionCounter, XrefCategory.FIELD_TYPE,
            )
            instructionCounter++
        }

        override fun visitLdcInsn(value: Any?) {
            if (value is Type && (value.sort == Type.OBJECT || value.sort == Type.ARRAY)) {
                addTypeUsersForDescriptor(
                    classUsers, value.descriptor, callerClass, callerMethod, callerMethodDescriptor,
                    instructionCounter, XrefCategory.LDC_TYPE,
                )
            }
            instructionCounter++
        }
    }

    private fun addClassUser(
        classUsers: MutableMap<String, MutableList<XrefSite>>,
        targetInternalOrFqn: String,
        callerClassInternal: String,
        callerMethod: String,
        callerMethodDescriptor: String,
        instructionOffset: Int,
        category: XrefCategory,
    ) {
        val targetFqn = targetInternalOrFqn.replace('/', '.')
        if (isExcludedTarget(targetFqn)) return

        val site = XrefSite(
            callerClassFqn = callerClassInternal.replace('/', '.'),
            callerMethodName = callerMethod,
            callerMethodDescriptor = callerMethodDescriptor,
            instructionOffset = instructionOffset,
            category = category,
        )
        classUsers.getOrPut(targetFqn) { mutableListOf() }.add(site)
    }

    /**
     * Walk a JVM type descriptor and emit a class-user entry for every
     * non-primitive object type found inside it. Handles arrays
     * (`[Lcom/foo/Bar;`) and pure object types (`Lcom/foo/Bar;`); skips
     * primitives (`I`, `J`, `V`, etc.).
     */
    private fun addTypeUsersForDescriptor(
        classUsers: MutableMap<String, MutableList<XrefSite>>,
        descriptor: String,
        callerClassInternal: String,
        callerMethod: String,
        callerMethodDescriptor: String,
        instructionOffset: Int,
        category: XrefCategory,
    ) {
        var t = Type.getType(descriptor)
        // Unwrap arrays.
        while (t.sort == Type.ARRAY) t = t.elementType
        if (t.sort != Type.OBJECT) return
        addClassUser(
            classUsers, t.internalName, callerClassInternal, callerMethod,
            callerMethodDescriptor, instructionOffset, category,
        )
    }

    private fun isExcludedTarget(fqn: String): Boolean =
        excludedClassPrefixes.any { prefix ->
            fqn == prefix || fqn.startsWith("$prefix.")
        }

    private fun invokeCategory(opcode: Int): XrefCategory = when (opcode) {
        Opcodes.INVOKEVIRTUAL -> XrefCategory.INVOKE_VIRTUAL
        Opcodes.INVOKESTATIC -> XrefCategory.INVOKE_STATIC
        Opcodes.INVOKESPECIAL -> XrefCategory.INVOKE_SPECIAL
        Opcodes.INVOKEINTERFACE -> XrefCategory.INVOKE_INTERFACE
        Opcodes.INVOKEDYNAMIC -> XrefCategory.INVOKE_DYNAMIC
        else -> XrefCategory.INVOKE_VIRTUAL
    }

    companion object {
        /**
         * Default exclusions for the class-users map. JDK + Bytesight-runtime.
         * Mirrors composeApp's `AgentRuntimeFilter.EXCLUDED_PREFIXES` but
         * lives here in core where the indexer can apply it without taking
         * a composeApp dependency. The composeApp wrapper can pass a wider
         * list if needed (e.g., to also exclude the user's own libraries).
         */
        val DEFAULT_EXCLUDED_PREFIXES: List<String> = listOf(
            "java", "javax", "sun", "jdk",
            // Bytesight runtime + dependencies that ship inside the agent JAR.
            "com.bugdigger.agent",
            "com.bugdigger.protocol",
            "io.grpc",
            "io.perfmark",
            "net.bytebuddy",
            "org.objectweb.asm",
            "com.google.protobuf",
            "org.slf4j",
            "ch.qos.logback",
            "org.jcp",
        )
    }
}
