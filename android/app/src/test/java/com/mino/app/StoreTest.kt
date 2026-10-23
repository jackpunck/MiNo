package com.mino.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoreTest {
    private fun root() = Files.createTempDirectory("mino-store-test").toFile()
    @Test fun failedSaveThrowsInsteadOfReturningUnsavedSnapshot() {
        val dir = root()
        try {
            File(dir, "data.json.tmp").mkdir()
            assertThrows(Exception::class.java) { Store(dir).transact { it.copy(todos = listOf(Todo(id = "kept"))) } }
        } finally { dir.deleteRecursively() }
    }
    @Test fun unreadableSourceCannotBeOverwrittenWithDefaults() {
        val dir = root()
        try {
            File(dir, "data.json").mkdir()
            assertThrows(Exception::class.java) { Store(dir).transact { AppData() } }
            assertTrue(File(dir, "data.json").isDirectory)
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptSourceIsPreservedAndBlocksWrites() {
        val dir = root()
        try {
            File(dir, "data.json").writeText("{broken")
            repeat(2) { assertThrows(Exception::class.java) { Store(dir).transact { AppData() } } }
            assertEquals("{broken", File(dir, "data.json").readText())
            val backups = dir.listFiles()!!.filter { it.name.endsWith(".broken") }
            assertEquals(1, backups.size)
            assertTrue(backups.all { it.readText() == "{broken" })
        } finally { dir.deleteRecursively() }
    }
    @Test fun successfulTransactionSurvivesNewStoreInstance() {
        val dir = root()
        try {
            Store(dir).transact { it.copy(todos = listOf(Todo(id = "persisted"))) }
            assertEquals("persisted", Store(dir).read().todos.single().id)
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedReplacementPreservesLastDurableSnapshot() {
        val dir = root()
        try {
            Store(dir).transact { it.copy(todos = listOf(Todo(id = "original"))) }
            File(dir, "data.json.tmp").mkdir()
            assertThrows(Exception::class.java) {
                Store(dir).transact { it.copy(todos = listOf(Todo(id = "unsaved"))) }
            }
            assertEquals("original", Store(dir).read().todos.single().id)
        } finally { dir.deleteRecursively() }
    }
    @Test fun repeatedCorruptReadsDeduplicateButPreserveDifferentContent() {
        val dir = root()
        try {
            File(dir, "data.json").writeText("{broken-one")
            repeat(25) { assertThrows(Exception::class.java) { Store(dir).read() } }
            assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".broken") })
            File(dir, "data.json").writeText("{broken-two")
            repeat(25) { assertThrows(Exception::class.java) { Store(dir).read() } }
            val backups = dir.listFiles()!!.filter { it.name.endsWith(".broken") }
            assertEquals(setOf("{broken-one", "{broken-two"), backups.map { it.readText() }.toSet())
            assertEquals("{broken-two", File(dir, "data.json").readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptBackupPreservesOriginalBytes() {
        val dir = root()
        try {
            val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x7b)
            File(dir, "data.json").writeBytes(bytes)
            repeat(2) { assertThrows(Exception::class.java) { Store(dir).read() } }
            val backup = dir.listFiles()!!.single { it.name.endsWith(".broken") }
            assertArrayEquals(bytes, backup.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
