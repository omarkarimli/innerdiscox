package com.omarkarimli.innerdiscox.cipher

import com.omarkarimli.innerdiscox.utils.sha1
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DiscoEjsAndroidResourcesTest {
    @Test
    fun publishedAarContainsTheSolverScriptsUsedByFullSolveTests() {
        val expected =
            mapOf(
                "disco_ejs/disco.solver.core.min.js" to "f59cd0c507ed083a8f115e3071703d2cd5ebbd74",
                "disco_ejs/disco.solver.lib.min.js" to "b498f8d73c89a5b726d9d3d95856313fc2788fed",
            )
        val packaged = mutableMapOf<String, String>()
        ZipFile(assertNotNull(System.getProperty("innerdiscox.test.aar"))).use { aar ->
            val classes = assertNotNull(aar.getEntry("classes.jar"))
            JarInputStream(aar.getInputStream(classes)).use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    if (entry.name in expected) {
                        packaged[entry.name] = sha1(jar.readBytes().decodeToString())
                    }
                }
            }
        }
        assertEquals(expected, packaged)
        for ((path, hash) in expected) {
            assertEquals(hash, sha1(readDiscoEjsSolverScript(path.substringAfter('/'))))
        }
    }
}
