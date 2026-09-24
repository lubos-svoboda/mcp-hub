package cz.lubos.mcphub.grafana

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly the certificates in one PEM file, the way `curl --cacert` does. A bundle holding
 * several certificates is read whole.
 */
fun trustManagerFor(caFile: Path): X509TrustManager {
    require(Files.isReadable(caFile)) { "Certificate file $caFile does not exist or cannot be read." }
    val certificates = Files.newInputStream(caFile).use { input ->
        CertificateFactory.getInstance("X.509").generateCertificates(input)
    }
    require(certificates.isNotEmpty()) { "Certificate file $caFile holds no certificate." }

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        certificates.forEachIndexed { index, certificate -> setCertificateEntry("ca-$index", certificate) }
    }
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(keyStore)
    return trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
}

fun sslContextTrusting(caFile: Path): SSLContext =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManagerFor(caFile)), null) }
