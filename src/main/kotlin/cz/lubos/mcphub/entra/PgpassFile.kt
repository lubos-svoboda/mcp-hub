package cz.lubos.mcphub.entra

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** One line of a PostgreSQL password file; the database is always `*`, as one token serves them all. */
data class PgpassEntry(val host: String, val port: Int, val user: String, val password: String) {
    fun render() = listOf(host, port.toString(), "*", user, password).joinToString(":") { escape(it) }

    override fun toString() = "PgpassEntry(host=$host, port=$port, user=$user)"

    private fun escape(field: String) = field.replace("\\", "\\\\").replace(":", "\\:")
}

/**
 * Updates a PostgreSQL password file in place of the lines for the given hosts and users, leaving
 * every other line alone. The hub's own lines go first, because libpq uses the first line that
 * matches. The file is replaced in one step, so a client never reads it half written.
 */
class PgpassFile(private val path: Path) {

    fun update(managed: Set<Pair<String, String>>, entries: List<PgpassEntry>) {
        val kept = if (Files.exists(path)) {
            Files.readAllLines(path).filterNot { line -> hostAndUser(line)?.let { it in managed } == true }
        } else {
            emptyList()
        }
        val content = (entries.map(PgpassEntry::render) + kept).joinToString("") { "$it\n" }

        val temporary = Files.createTempFile(path.toAbsolutePath().parent, ".pgpass", ".tmp")
        try {
            Files.writeString(temporary, content)
            // libpq ignores a password file others may read; only POSIX file systems have the notion.
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (refused: FileSystemException) {
                // Windows refuses to replace a file another program holds open, and some mounted file
                // systems cannot rename atomically; writing in place is the fallback there.
                Files.writeString(path, content)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Host and user of a line, honouring the backslash escapes; null for comments and malformed lines. */
    private fun hostAndUser(line: String): Pair<String, String>? {
        if (line.isBlank() || line.startsWith("#")) {
            return null
        }
        val fields = mutableListOf(StringBuilder())
        var escaped = false
        for (character in line) {
            when {
                escaped -> fields.last().append(character).also { escaped = false }
                character == '\\' -> escaped = true
                character == ':' && fields.size < 5 -> fields.add(StringBuilder())
                else -> fields.last().append(character)
            }
        }
        return if (fields.size == 5) fields[0].toString() to fields[3].toString() else null
    }
}
