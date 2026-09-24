import kotlin.system.exitProcess

// Hand-rolled test runner, ported from shelf's TestMain.kt -- no JUnit/Gradle, see AGENTS.md's
// Testing section. Register each new test file's compiled class name here (Kotlin compiles
// top-level functions in FooTest.kt into a class FooTestKt) -- there's no annotation-driven
// discovery without a real test framework, so this list is the only thing that finds them.
private val testClasses = listOf(
    Class.forName("SidecarIngredientLineParserTestKt"),
)

fun main() {
    var passed = 0
    var failed = 0

    for (clazz in testClasses) {
        val testMethods = clazz.declaredMethods.filter { it.name.startsWith("test") && it.parameterCount == 0 }
        for (method in testMethods) {
            try {
                method.invoke(null)
                println("PASS ${clazz.simpleName}.${method.name}")
                passed++
            } catch (e: Exception) {
                println("FAIL ${clazz.simpleName}.${method.name}: ${(e.cause ?: e).message}")
                failed++
            }
        }
    }

    println("---")
    println("$passed passed, $failed failed")
    if (failed > 0) exitProcess(1)
}
