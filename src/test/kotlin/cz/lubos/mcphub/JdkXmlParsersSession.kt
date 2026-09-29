package cz.lubos.mcphub

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * A test starting the application does not pass through main, so the XML parsers main sets are
 * set here before the first test, in Gradle and in the IDE alike.
 */
class JdkXmlParsersSession : LauncherSessionListener {

    override fun launcherSessionOpened(session: LauncherSession) = useJdkXmlParsers()
}
