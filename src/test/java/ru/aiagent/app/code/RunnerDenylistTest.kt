package ru.aiagent.app.code

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Регресс на денилисты побега run_kotlin/run_java (несандбоксируемые интерпретаторы). Ключевая
 * находка аудита: System.load/loadLibrary грузит .so → JNI_OnLoad = произвольный нативный код,
 * а раньше эти вызовы в списке отсутствовали (код мог записать .so в filesDir и загрузить).
 */
class RunnerDenylistTest {

    @Test
    fun kotlin_blocks_native_load() {
        // Нативный побег — теперь ловится (был дырой).
        assertNotNull(forbiddenInKotlin("""System.load("/data/data/ru.aiagent.app/files/x.so")"""))
        assertNotNull(forbiddenInKotlin("""System.loadLibrary("evil")"""))
        assertNotNull(forbiddenInKotlin("Sys tem . load ( x )")) // пробельный обход
        // Ранее покрытые классы побега — по-прежнему ловятся.
        assertNotNull(forbiddenInKotlin("Runtime.getRuntime().exec(\"sh\")"))
        assertNotNull(forbiddenInKotlin("ProcessBuilder(\"sh\").start()"))
    }

    @Test
    fun java_blocks_native_load() {
        assertNotNull(forbiddenInJava("""System.load("/tmp/x.so");"""))
        assertNotNull(forbiddenInJava("""System.loadLibrary("evil");"""))
        assertNotNull(forbiddenInJava("Runtime.getRuntime().exec(\"sh\")"))
    }

    @Test
    fun benign_code_allowed() {
        assertNull(forbiddenInKotlin("val x = (1..10).sum(); println(x)"))
        assertNull(forbiddenInJava("int x = 6 * 7; System.out.println(x);")) // System.out ≠ System.load
    }
}
