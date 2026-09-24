package cz.lubos.mcphub.grafana

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class CertificateTrustTest {

    @TempDir
    lateinit var directory: Path

    /** Real authorities taken from the JDK, so the test needs no certificate of its own. */
    private val jdkAuthorities: List<X509Certificate> =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
            .acceptedIssuers.toList()

    /** keytool would import only the first certificate of a bundle; this reads all of them. */
    @Test
    fun `every certificate of a bundle is trusted and nothing else`() {
        val bundle = jdkAuthorities.take(2)
        val caFile = directory.resolve("bundle.pem")
        Files.writeString(caFile, bundle.joinToString("") { certificate -> pem(certificate) })

        assertThat(trustManagerFor(caFile).acceptedIssuers.toList()).containsExactlyInAnyOrderElementsOf(bundle)
    }

    @Test
    fun `a missing file is reported by its path`() {
        val missing = directory.resolve("missing.pem")

        val failure = assertThrows<IllegalArgumentException> { trustManagerFor(missing) }

        assertThat(failure.message).contains(missing.toString())
    }

    @Test
    fun `a file holding no certificate is refused`() {
        val empty = Files.createFile(directory.resolve("empty.pem"))

        val failure = assertThrows<IllegalArgumentException> { trustManagerFor(empty) }

        assertThat(failure.message).contains("holds no certificate")
    }

    private fun pem(certificate: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) +
            "\n-----END CERTIFICATE-----\n"
}
