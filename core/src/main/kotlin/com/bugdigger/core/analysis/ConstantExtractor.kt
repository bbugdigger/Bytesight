package com.bugdigger.core.analysis

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Types of constants that can be extracted from bytecode.
 */
enum class ConstantType {
    STRING, INTEGER, LONG, FLOAT, DOUBLE, CLASS_REF, METHOD_HANDLE
}

/**
 * Patterns for highlighting interesting string constants.
 */
enum class StringPattern(val regex: Regex, val label: String) {
    URL(Regex("https?://.*"), "URL"),
    FILE_PATH(Regex("[/\\\\].*\\.[a-zA-Z]{2,4}"), "File Path"),
    IP_ADDRESS(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"), "IP Address"),
    CRYPTO_KEY(Regex("[A-Fa-f0-9]{32,}"), "Crypto/Hex Key"),
    EMAIL(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "Email"),
    SQL(Regex("(?i)(SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER)\\s+.*"), "SQL"),
    ;

    companion object {
        /**
         * Finds all patterns that match the given string value.
         */
        fun matchAll(value: String): Set<StringPattern> {
            return entries.filter { it.regex.containsMatchIn(value) }.toSet()
        }
    }
}

/**
 * Represents a constant value extracted from bytecode.
 */
data class ExtractedConstant(
    val value: Any,
    val type: ConstantType,
    val className: String,
    val methodName: String?,
    val location: String,
    val matchedPatterns: Set<StringPattern> = emptySet(),
)

/**
 * Extracts hardcoded constants (strings, numbers, class references) from JVM bytecode.
 *
 * Uses ASM's [ClassReader] and [ClassVisitor] to scan LDC instructions and constant
 * pool entries for embedded literals.
 */
class ConstantExtractor {

    /**
     * Extracts all constants from the given class bytecode.
     *
     * @param bytecode Raw class file bytes
     * @return List of extracted constants found in the class
     */
    fun extract(bytecode: ByteArray): List<ExtractedConstant> {
        val constants = mutableListOf<ExtractedConstant>()
        val reader = ClassReader(bytecode)
        val className = reader.className.replace('/', '.')

        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitLdcInsn(value: Any?) {
                        if (value == null) return

                        val (constantType, constantValue) = when (value) {
                            is String -> ConstantType.STRING to value
                            is Int -> ConstantType.INTEGER to value
                            is Long -> ConstantType.LONG to value
                            is Float -> ConstantType.FLOAT to value
                            is Double -> ConstantType.DOUBLE to value
                            is Type -> ConstantType.CLASS_REF to value.className
                            else -> return
                        }

                        val patterns = if (constantType == ConstantType.STRING) {
                            StringPattern.matchAll(constantValue.toString())
                        } else {
                            emptySet()
                        }

                        val location = "$className.$name()"
                        constants.add(
                            ExtractedConstant(
                                value = constantValue,
                                type = constantType,
                                className = className,
                                methodName = name,
                                location = location,
                                matchedPatterns = patterns,
                            )
                        )
                    }

                    override fun visitIntInsn(opcode: Int, operand: Int) {
                        // BIPUSH and SIPUSH push integer constants
                        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                            constants.add(
                                ExtractedConstant(
                                    value = operand,
                                    type = ConstantType.INTEGER,
                                    className = className,
                                    methodName = name,
                                    location = "$className.$name()",
                                )
                            )
                        }
                    }
                }
            }
        }, ClassReader.SKIP_FRAMES)

        return constants
    }
}
