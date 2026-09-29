package cz.lubos.mcphub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.xml.parsers.SAXParserFactory

/** Logback turns off external entities before it reads its configuration; Oracle's parser refuses that and the server did not start. */
class XmlParsersTest {

    /**
     * JdkXmlParsersSession sets the same parsers before every test run, so they are cleared here
     * first: otherwise this would pass whatever useJdkXmlParsers does.
     */
    @Test
    fun `the parser Logback gets supports the features it sets, although Oracle's parser is on the classpath`() {
        val configured = FACTORIES.associateWith(System::getProperty)
        try {
            FACTORIES.forEach(System::clearProperty)
            assertThat(SAXParserFactory.newInstance().javaClass.name).startsWith("oracle.xml")

            useJdkXmlParsers()
            val factory = SAXParserFactory.newInstance()
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)

            assertThat(factory.javaClass.name).startsWith("com.sun.org.apache.xerces")
        } finally {
            configured.forEach { (factory, implementation) ->
                if (implementation == null) System.clearProperty(factory) else System.setProperty(factory, implementation)
            }
        }
    }

    private companion object {
        val FACTORIES = listOf(
            "javax.xml.parsers.DocumentBuilderFactory",
            "javax.xml.parsers.SAXParserFactory",
            "javax.xml.transform.TransformerFactory",
        )
    }
}
