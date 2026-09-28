package com.localchatbot.domain.tools

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DangerousCommandsTest {

    @Test
    fun detectaBorrarLaRaizOElHome() {
        listOf("rm -rf /", "rm -rf ~", "rm -rf /*", "rm -fr / ; echo hecho", "rm -r -f \"/\"").forEach {
            assertNotNull(DangerousCommands.match(it), it)
        }
    }

    @Test
    fun borrarSubdirectoriosEsUsoNormal() {
        listOf("rm -rf /tmp/build", "rm -rf ~/old", "rm -rf build/", "rm archivo.txt").forEach {
            assertNull(DangerousCommands.match(it), it)
        }
    }

    @Test
    fun detectaSudoSoloComoComando() {
        assertNotNull(DangerousCommands.match("sudo apt install jq"))
        assertNotNull(DangerousCommands.match("ls && sudo rm x"))
        assertNull(DangerousCommands.match("echo sudo"))
    }

    @Test
    fun detectaElRestoDePatrones() {
        listOf(
            ":(){ :|:& };:",
            "mkfs.ext4 /dev/sdb1",
            "dd if=img of=/dev/disk2",
            "cat x > /dev/sda",
            "shutdown -h now",
            "chmod -R 777 /",
            "kill -9 -1",
            "diskutil eraseDisk JHFS+ X disk2",
            "format c:"
        ).forEach { assertNotNull(DangerousCommands.match(it), it) }
    }

    @Test
    fun comandosHabitualesNoMatchean() {
        listOf("git status", "./gradlew build", "npm run dev", "chmod +x script.sh", "ls -la /").forEach {
            assertNull(DangerousCommands.match(it), it)
        }
    }
}
