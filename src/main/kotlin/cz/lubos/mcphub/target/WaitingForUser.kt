package cz.lubos.mcphub.target

/**
 * Marks a failure that lasts until someone acts, for example signs in on the status page. The
 * probe logs it as information rather than as a fault.
 */
interface WaitingForUser
