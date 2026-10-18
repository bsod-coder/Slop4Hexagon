/*
 * Reflective test runner for air-gapped environments (no Maven access).
 * Runs @Test methods of the classes named on the command line. Exits non-zero
 * on any failure. See tools/offline-test-runner/README.md.
 */
import java.lang.reflect.Modifier

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: OfflineTestRunner <fqcn> [<fqcn> ...]")
        kotlin.system.exitProcess(2)
    }
    var passed = 0
    var failed = 0
    for (className in args) {
        val clazz = Class.forName(className)
        if (Modifier.isAbstract(clazz.modifiers)) continue
        val beforeMethods = clazz.methods.filter { it.isAnnotationPresent(org.junit.Before::class.java) }
        val testMethods = clazz.methods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }
            .sortedBy { it.name }
        for (method in testMethods) {
            val label = "${clazz.simpleName}.${method.name}"
            val expectedClass = method.getAnnotation(org.junit.Test::class.java).expected.java
            val expectsThrow = expectedClass != org.junit.TestNone::class.java
            try {
                val instance = clazz.getDeclaredConstructor().newInstance()
                for (before in beforeMethods) {
                    before.invoke(instance)
                }
                method.invoke(instance)
                if (expectsThrow) {
                    println("FAIL  $label")
                    println("      expected exception ${expectedClass.name} was not thrown")
                    failed++
                } else {
                    println("PASS  $label")
                    passed++
                }
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                if (expectsThrow && expectedClass.isInstance(cause)) {
                    println("PASS  $label")
                    passed++
                } else {
                    println("FAIL  $label")
                    println("      ${cause::class.java.name}: ${cause.message}")
                    cause.stackTrace.take(6).forEach { println("      at $it") }
                    failed++
                }
            }
        }
    }
    println()
    println("Result: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
