package cz.lubos.mcphub.entra

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * libpq matches a line by its first four fields and uses the first match, so the tests pin the
 * exact text of every line, not merely that a token is somewhere in the file.
 */
class PgpassFileTest {

    @TempDir
    lateinit var directory: Path

    private val file by lazy { directory.resolve("pgpass.conf") }
    private val managed = setOf(HOST to USER)

    @Test
    fun `a missing file is created with one line per login`() {
        PgpassFile(file).update(managed, listOf(PgpassEntry(HOST, 5432, USER, "token-1")))

        assertThat(Files.readString(file)).isEqualTo("$HOST:5432:*:$USER:token-1\n")
    }

    @Test
    fun `other lines stay, the hub's go first and its stale ones are replaced`() {
        Files.writeString(
            file,
            "# my own passwords\r\n" +
                "localhost:5432:*:postgres:secret\r\n" +
                "$HOST:*:*:$USER:expired-token\r\n" +
                "$HOST:5432:reporting:$USER:another-expired-token\r\n",
        )

        PgpassFile(file).update(managed, listOf(PgpassEntry(HOST, 5432, USER, "token-2")))

        assertThat(Files.readAllLines(file)).containsExactly(
            "$HOST:5432:*:$USER:token-2",
            "# my own passwords",
            "localhost:5432:*:postgres:secret",
        )
    }

    @Test
    fun `no entries remove the hub's lines and nothing else`() {
        Files.writeString(file, "$HOST:5432:*:$USER:token-1\nlocalhost:5432:*:postgres:secret\n")

        PgpassFile(file).update(managed, emptyList())

        assertThat(Files.readAllLines(file)).containsExactly("localhost:5432:*:postgres:secret")
    }

    @Test
    fun `colons and backslashes are escaped the way libpq reads them`() {
        PgpassFile(file).update(setOf("db:1" to "user\\x"), listOf(PgpassEntry("db:1", 5432, "user\\x", "a:b")))

        assertThat(Files.readString(file)).isEqualTo("db\\:1:5432:*:user\\\\x:a\\:b\n")

        // Read back through the same escapes, the line is recognised as the hub's own.
        PgpassFile(file).update(setOf("db:1" to "user\\x"), emptyList())
        assertThat(Files.readString(file)).isEmpty()
    }

    /** libpq ignores the file otherwise; the check only exists where the file system knows permissions. */
    @Test
    fun `the file is readable by its owner only`() {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))

        PgpassFile(file).update(managed, listOf(PgpassEntry(HOST, 5432, USER, "token-1")))

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------")
    }

    @Test
    fun `no temporary file is left behind`() {
        PgpassFile(file).update(managed, listOf(PgpassEntry(HOST, 5432, USER, "token-1")))

        assertThat(Files.list(directory).use { it.map { path -> path.fileName.toString() }.toList() })
            .containsExactly("pgpass.conf")
    }

    private companion object {
        const val HOST = "reporting.postgres.database.azure.com"
        const val USER = "app_readers"
    }
}
