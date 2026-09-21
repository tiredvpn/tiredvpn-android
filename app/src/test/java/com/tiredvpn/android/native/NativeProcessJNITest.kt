package com.tiredvpn.android.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JNI wrapper's behaviour when there is no core to talk to.
 *
 * A JVM unit test never has libtiredvpn.so, which is exactly the situation
 * defect 5.2 is about: a device whose ABI has no .so, or a build whose Go
 * exports drifted from the `external fun` declarations. `UnsatisfiedLinkError`
 * is an Error, so the `catch (e: Exception)` that used to surround `start()`
 * did not catch it and the app died on the first external call.
 */
class NativeProcessJNITest {

    private class Recorder {
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val exits = mutableListOf<Int>()
    }

    private fun process(rec: Recorder) = NativeProcessJNI(
        args = listOf("-server", "example.org:443", "-secret", "abc", "-tun"),
        onOutput = { rec.output.add(it) },
        onError = { rec.errors.add(it) },
        onExit = { rec.exits.add(it) },
    )

    @Test
    fun `the library really is unavailable in this environment`() {
        // Positive control: every assertion below is about the missing-library
        // path, and would pass vacuously if the flag said otherwise.
        assertFalse("libtiredvpn.so must not be loadable in a JVM unit test", TiredVpnNative.isAvailable)
    }

    @Test
    fun `starting without a core reports an error instead of throwing`() {
        val rec = Recorder()
        val process = process(rec)

        process.start()

        assertFalse("nothing was started", process.isRunning)
        assertEquals("exactly one exit must be reported", 1, rec.exits.size)
        assertEquals(NativeProcessJNI.EXIT_NO_NATIVE_LIBRARY, rec.exits.single())
        assertTrue("the reason has to reach the caller", rec.errors.any { it.contains("Native core library missing") })
    }

    @Test
    fun `the missing-library exit is reported once`() {
        val rec = Recorder()
        val process = process(rec)

        process.start()
        process.start()

        assertEquals(1, rec.exits.size)
    }

    @Test
    fun `stopping something that never started reports nothing`() {
        val rec = Recorder()
        val process = process(rec)

        process.stop()

        assertTrue(rec.exits.isEmpty())
        assertFalse(process.isRunning)
    }

    /**
     * Cross-talk guard. `startClient` on the Go side cancels the previous
     * client without waiting for its goroutine, so a superseded core's terminal
     * callback can arrive after a new NativeProcessJNI has registered itself.
     * Acting on it would mark a freshly started core dead.
     */
    @Test
    fun `a terminal state for a core this instance never started is ignored`() {
        val rec = Recorder()
        val process = process(rec)

        process.onStateChange("disconnected", "{}")
        process.onStateChange("error", """{"error":"boom"}""")

        assertTrue("no exit may be reported for somebody else's core", rec.exits.isEmpty())
        assertFalse(process.isRunning)
    }

    @Test
    fun `state changes are still forwarded as output`() {
        val rec = Recorder()
        val process = process(rec)

        process.onStateChange("connecting", "{}")
        process.onLogMessage("hello")

        assertTrue(rec.output.any { it.contains("[STATE] connecting") })
        assertTrue(rec.output.contains("hello"))
    }

    @Test
    fun `an error state reaches the caller even when it is ignored as terminal`() {
        val rec = Recorder()
        val process = process(rec)

        process.onStateChange("error", """{"error":"handshake EOF"}""")

        // org.json is stubbed in unit tests and returns defaults, so the
        // message text is whatever the fallback produced - what matters here is
        // that onError fired at all.
        assertTrue("the error has to be surfaced", rec.errors.isNotEmpty())
    }
}
