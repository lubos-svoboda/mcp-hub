package cz.lubos.mcphub.support

import cz.lubos.mcphub.target.ConnectionProbe
import java.time.Duration

/** Waits for the background checks to finish, failing rather than hanging when one never does. */
fun ConnectionProbe.awaitIdle(timeout: Duration = Duration.ofSeconds(5)) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (isProbing) {
        check(System.nanoTime() < deadline) { "The checks did not finish within $timeout" }
        Thread.sleep(10)
    }
}
