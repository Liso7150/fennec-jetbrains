package fennec.plugin

import kotlin.system.exitProcess

/** Minimal headless test runner: no IDE, no network, no test framework. */
object TestMain {

    private var passed = 0
    private var failed = 0
    private val failures = mutableListOf<String>()

    fun check(name: String, block: () -> Unit) {
        try {
            block()
            passed++
            println("PASS $name")
        } catch (e: Throwable) {
            failed++
            failures.add("$name -> ${e.message}")
            println("FAIL $name -> ${e.message}")
        }
    }

    fun expect(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    fun <T> expectEquals(expected: T, actual: T, message: String) {
        if (expected != actual) throw AssertionError("$message: expected <$expected> but was <$actual>")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        SharedConfigTest.run()
        AirAgentRegistrarTest.run()
        println("----------------------------------------")
        println("passed=$passed failed=$failed")
        if (failed > 0) {
            failures.forEach { println("FAILURE $it") }
            exitProcess(1)
        }
    }
}