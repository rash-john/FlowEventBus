package com.floweventbus.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate

class SubscriberIndexProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment) =
        SubscriberIndexProcessor(environment.codeGenerator, environment.logger, environment.options[SubscriberIndexProcessor.INDEX_OPTION])
}

/**
 * Generates an object implementing `SubscriberIndex` for every class with `@Subscribe` methods. Each entry
 * calls the method directly, so `register` needs no reflection. A class the generated code can't call
 * (private, local, generic event type, ...) is left out with a warning and found by reflection at runtime.
 */
class SubscriberIndexProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val indexName: String?,
) : SymbolProcessor {

    private val classes = LinkedHashMap<String, IndexedClass>()
    private val sources = LinkedHashSet<KSFile>()

    private class IndexedClass(val name: String, val methods: List<String>)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val (valid, deferred) = resolver.getSymbolsWithAnnotation(SUBSCRIBE).partition { it.validate() }
        valid.filterIsInstance<KSFunctionDeclaration>()
            .groupBy { it.parentDeclaration }
            .forEach { (parent, functions) ->
                if (parent !is KSClassDeclaration) {
                    functions.forEach { logger.error("@Subscribe functions must be members of a class", it) }
                } else {
                    indexClass(parent, functions)
                }
            }
        return deferred
    }

    private fun indexClass(clazz: KSClassDeclaration, functions: List<KSFunctionDeclaration>) {
        val qualifiedName = clazz.qualifiedName?.asString()
        if (qualifiedName == null || !isAccessible(clazz)) return skip(clazz, "it isn't visible to the generated index")
        if (qualifiedName in classes) return
        val className = escape(qualifiedName)
        val receiver = if (clazz.typeParameters.isEmpty()) className else "$className<${clazz.typeParameters.joinToString { "*" }}>"
        val methods = ArrayList<String>()
        for (function in functions) {
            val visibility = function.getVisibility()
            if (visibility != Visibility.PUBLIC && visibility != Visibility.INTERNAL) {
                return skip(clazz, "${function.simpleName.asString()} isn't public or internal")
            }
            if (function.functionKind != FunctionKind.MEMBER || function.extensionReceiver != null || function.parameters.size != 1) {
                return skip(clazz, "${function.simpleName.asString()} isn't a member function with exactly one parameter")
            }
            val eventType = function.parameters.single().type.resolve()
            val eventClass = classOf(eventType)
            val eventName = eventClass?.qualifiedName?.asString()
            if (eventClass == null || eventName == null || eventClass.typeParameters.isNotEmpty() ||
                (!eventType.isMarkedNullable && eventName in PRIMITIVES)
            ) {
                return skip(clazz, "the event type of ${function.simpleName.asString()} can't be indexed")
            }
            val annotation = function.annotations.first { it.annotationType.resolve().declaration.qualifiedName?.asString() == SUBSCRIBE }
            val args = annotation.arguments.associate { it.name?.asString() to it.value }
            val threadMode = when (val mode = args["threadMode"]) {
                is KSType -> mode.declaration.simpleName.asString()
                is KSDeclaration -> mode.simpleName.asString()
                null -> "POSTING"
                else -> mode.toString().substringAfterLast('.')
            }
            val name = function.simpleName.asString()
            val event = escape(eventName)
            // The lambda is `suspend`, so plain and suspend handlers are called the same way.
            methods += "SubscriberMethod(\"$name\", $event::class.javaObjectType, ThreadMode.$threadMode, " +
                "${args["priority"] ?: 0}, ${args["sticky"] ?: false}) { s, e -> (s as $receiver).${escape(name)}(e as $event) }"
        }
        classes[qualifiedName] = IndexedClass(className, methods)
        clazz.containingFile?.let(sources::add)
    }

    override fun finish() {
        if (classes.isEmpty()) return
        if (indexName == null) {
            logger.error("No index class name: add ksp { arg(\"$INDEX_OPTION\", \"com.example.MyEventBusIndex\") }")
            return
        }
        val packageName = indexName.substringBeforeLast('.', "")
        val simpleName = indexName.substringAfterLast('.')
        val entries = classes.values.joinToString("\n") { indexed ->
            "        ${indexed.name}::class.java to listOf(\n" +
                indexed.methods.joinToString("") { "            $it,\n" } +
                "        ),"
        }
        val code = buildString {
            appendLine("// Generated by floweventbus-ksp. Do not edit.")
            if (packageName.isNotEmpty()) appendLine("package ${escape(packageName)}\n")
            appendLine("import com.floweventbus.SubscriberIndex")
            appendLine("import com.floweventbus.SubscriberMethod")
            appendLine("import com.floweventbus.ThreadMode")
            appendLine()
            appendLine("object $simpleName : SubscriberIndex {")
            appendLine("    private val index: Map<Class<*>, List<SubscriberMethod>> = mapOf(")
            appendLine(entries)
            appendLine("    )")
            appendLine()
            appendLine("    override fun subscriberMethods(subscriberClass: Class<*>): List<SubscriberMethod>? = index[subscriberClass]")
            appendLine("}")
        }
        codeGenerator.createNewFile(Dependencies(true, *sources.toTypedArray()), packageName, simpleName)
            .bufferedWriter().use { it.write(code) }
    }

    private fun skip(clazz: KSClassDeclaration, reason: String) =
        logger.warn("${clazz.qualifiedName?.asString() ?: clazz.simpleName.asString()} not indexed, will use reflection: $reason", clazz)

    /** The class and every class enclosing it must be callable from the index's package. */
    private fun isAccessible(clazz: KSClassDeclaration): Boolean {
        var current: KSDeclaration? = clazz
        while (current != null) {
            val visibility = current.getVisibility()
            if (visibility != Visibility.PUBLIC && visibility != Visibility.INTERNAL) return false
            current = current.parentDeclaration
        }
        return true
    }

    private fun classOf(type: KSType): KSClassDeclaration? {
        var declaration = type.declaration
        while (declaration is KSTypeAlias) declaration = declaration.type.resolve().declaration
        return declaration as? KSClassDeclaration
    }

    private fun escape(qualifiedName: String) =
        qualifiedName.split('.').joinToString(".") { if (it in KEYWORDS) "`$it`" else it }

    companion object {
        const val INDEX_OPTION = "floweventbus.index"
        private const val SUBSCRIBE = "com.floweventbus.Subscribe"
        private val PRIMITIVES = setOf("Int", "Long", "Short", "Byte", "Char", "Boolean", "Float", "Double").map { "kotlin.$it" }.toSet()
        private val KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
            "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
            "val", "var", "when", "while",
        )
    }
}
