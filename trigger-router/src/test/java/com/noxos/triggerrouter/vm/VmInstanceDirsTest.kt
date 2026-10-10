package com.noxos.triggerrouter.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmInstanceDirsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun instance(root: File, name: String): File =
        File(root, name).apply {
            mkdirs()
            File(this, "instance.img").writeBytes(ByteArray(2048))
            File(this, "idsig").writeBytes(ByteArray(64))
        }

    @Test
    fun releasingAnInstanceDeletesItsDirectoryAndContents() {
        val root = temp.newFolder("vm")
        val dirs = VmInstanceDirs(root)
        val name = "noxos-scan-aaaa"
        val dir = instance(root, name)
        dirs.register(name)

        assertTrue(dirs.release(name))

        assertFalse(dir.exists())
    }

    @Test
    fun releasingAnInstanceThatIsAlreadyGoneSucceeds() {
        val dirs = VmInstanceDirs(temp.newFolder("vm"))

        assertTrue(dirs.release("noxos-scan-never-created"))
    }

    @Test
    fun startupPurgeRemovesLeftoverScanInstancesButNotActiveOnesOrOtherFiles() {
        val root = temp.newFolder("vm")
        val dirs = VmInstanceDirs(root)
        val leftoverA = instance(root, "noxos-scan-leftover-a")
        val leftoverB = instance(root, "noxos-scan-leftover-b")
        val running = instance(root, "noxos-scan-running")
        val unrelated = instance(root, "someone-elses-vm")
        val strayFile = File(root, "noxos-scan-file-not-dir").apply { writeText("x") }
        dirs.register("noxos-scan-running")

        val purged = dirs.purgeLeftovers()

        assertEquals(2, purged)
        assertFalse(leftoverA.exists())
        assertFalse(leftoverB.exists())
        assertTrue(running.exists())
        assertTrue(unrelated.exists())
        assertTrue(strayFile.exists())
        dirs.release("noxos-scan-running")
        assertFalse(running.exists())
    }

    @Test
    fun purgingWhenTheVmDirectoryDoesNotExistIsANoOp() {
        val dirs = VmInstanceDirs(File(temp.root, "no-such-dir"))

        assertEquals(0, dirs.purgeLeftovers())
    }
}
