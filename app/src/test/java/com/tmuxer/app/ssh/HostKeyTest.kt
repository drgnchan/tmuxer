package com.tmuxer.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostKeyTest {
    @Test
    fun readsAlgorithmNameFromKeyBlob() {
        val name = "ssh-ed25519".toByteArray()
        val blob = byteArrayOf(0, 0, 0, name.size.toByte()) + name + ByteArray(36)
        assertEquals("ssh-ed25519", sshKeyType(blob))
    }

    @Test
    fun rejectsMalformedKeyBlobs() {
        assertNull(sshKeyType(byteArrayOf(0, 0)))
        assertNull(sshKeyType(byteArrayOf(0, 0, 0, 20, 'a'.code.toByte())))
        assertNull(sshKeyType(byteArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun matchesJschHostAliasForNonDefaultPorts() {
        assertEquals("example.com", jschHostAlias("example.com", 22))
        assertEquals("[example.com]:2222", jschHostAlias("example.com", 2222))
    }

    @Test
    fun prefersTheAlgorithmOfTheTrustedKey() {
        assertEquals(
            "ecdsa-sha2-nistp256,ssh-ed25519,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa",
            preferredHostKeyAlgorithms("ecdsa-sha2-nistp256")
        )
        assertEquals(
            "rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521",
            preferredHostKeyAlgorithms("ssh-rsa")
        )
        assertEquals(
            "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa",
            preferredHostKeyAlgorithms(null)
        )
    }
}
