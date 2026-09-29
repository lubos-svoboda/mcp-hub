package cz.lubos.mcphub

import cz.lubos.mcphub.config.HubProperties

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// One DataSource per configured environment is built by the registry, so the single
// auto-configured DataSource is not wanted — without it Boot fails on a missing URL.
@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class])
@EnableConfigurationProperties(HubProperties::class)
@EnableScheduling
class McpHubApplication

fun main(args: Array<String>) {
	useJdkXmlParsers()
	runApplication<McpHubApplication>(*args)
}

/**
 * Oracle's xmlparserv2, needed to read XMLTYPE, registers itself as the JVM-wide XML parser, and
 * Logback then fails to read its configuration. The Oracle driver uses that parser directly, so
 * everything else keeps the JDK's own.
 */
fun useJdkXmlParsers() {
	mapOf(
		"javax.xml.parsers.DocumentBuilderFactory" to "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl",
		"javax.xml.parsers.SAXParserFactory" to "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl",
		"javax.xml.transform.TransformerFactory" to "com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl",
	).forEach { (factory, implementation) ->
		if (System.getProperty(factory) == null) {
			System.setProperty(factory, implementation)
		}
	}
}
