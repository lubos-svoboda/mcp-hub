package cz.lubos.mcphub.query

import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.support.PostgresTestDatabase
import cz.lubos.mcphub.support.PostgresTestDatabase.CZECH_LABEL
import cz.lubos.mcphub.support.PostgresTestDatabase.ENVIRONMENT_NAME
import cz.lubos.mcphub.support.PostgresTestDatabase.SAMPLE_ENUM
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/** PostgreSQL counterpart of [OracleColumnTypesIT]; the test container's session runs in UTC. */
@Tag("integration")
class PostgresColumnTypesIT {

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    fun `a column type comes back in its readable form`(label: String, expression: String, expected: String) {
        assertThat(runQuery("select $expression as v").rows.single().single()).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "text", "varchar(10)", "char(5)", "smallint", "integer", "bigint", "numeric", "numeric(38,10)",
            "real", "double precision", "money", "boolean",
            "date", "time", "timetz", "timestamp", "timestamptz", "interval",
            "bytea", "uuid", "json", "jsonb", "xml", "integer[]", "text[]", "inet", "bit(3)", "bit(1)", SAMPLE_ENUM,
        ],
    )
    fun `a null of a column type stays null`(type: String) {
        assertThat(runQuery("select cast(null as $type) as v").rows.single().single()).isNull()
    }

    private fun runQuery(sql: String): QueryResult =
        EnvironmentRegistry(PostgresTestDatabase.hubProperties()).use { registry ->
            QueryRunner(ReadOnlySession(), ResultMapper()).run(requireNotNull(registry.find(ENVIRONMENT_NAME)), sql)
        }

    private companion object {

        @JvmStatic
        fun values(): List<Arguments> = listOf(
            value("text with diacritics", "text '$CZECH_LABEL'", CZECH_LABEL),
            value("char keeps its padding", "cast('ab' as char(5))", "ab   "),
            value("smallint", "cast(-32768 as smallint)", "-32768"),
            value("bigint", "9223372036854775807", "9223372036854775807"),
            value("numeric keeps its scale", "numeric '0.10'", "0.10"),
            value("numeric without an exponent", "cast(1e30 as numeric)", "1000000000000000000000000000000"),
            value("numeric NaN", "numeric 'NaN'", "NaN"),
            value("numeric infinity", "numeric 'Infinity'", "Infinity"),
            value("real", "real '0.1'", "0.1"),
            value("double precision", "double precision '0.1'", "0.1"),
            value("double precision NaN", "double precision 'NaN'", "NaN"),
            value("money", "money '12.34'", "$12.34"),
            value("boolean", "true", "true"),
            value("date", "date '2026-03-15'", "2026-03-15"),
            value("date before Christ", "date '0044-03-15 BC'", "-0043-03-15"),
            value("date infinity", "date 'infinity'", "infinity"),
            value("time", "time '08:00:45.5'", "08:00:45.5"),
            value("time with time zone", "timetz '08:00:45+02'", "08:00:45+02"),
            value("timestamp at midnight", "timestamp '2026-03-15 00:00:00'", "2026-03-15T00:00:00"),
            value("timestamp with microseconds", "timestamp '2026-03-15 08:00:45.123456'", "2026-03-15T08:00:45.123456"),
            value("timestamp infinity", "timestamp 'infinity'", "infinity"),
            value("timestamp minus infinity", "timestamp '-infinity'", "-infinity"),
            value("timestamptz in another zone", "timestamptz '2026-03-15 08:00:45+02'", "2026-03-15T06:00:45Z"),
            value("timestamptz infinity", "timestamptz 'infinity'", "infinity"),
            value("interval", "interval '1 day 02:03:04'", "1 day 02:03:04"),
            value("bytea", "bytea '\\x010203'", "<binary, 3 bytes>"),
            value("uuid", "uuid 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
            value("json keeps its text", "json '{\"a\": 1,  \"b\": \"č\"}'", "{\"a\": 1,  \"b\": \"č\"}"),
            value("jsonb", "jsonb '{\"b\": \"č\", \"a\": 1}'", "{\"a\": 1, \"b\": \"č\"}"),
            value("xml", "xml '<a>1</a>'", "<a>1</a>"),
            value("integer array", "array[1, 2, 3]", "{1,2,3}"),
            value("text array with diacritics", "array['Přípojný bod', 'b']", "{\"Přípojný bod\",b}"),
            value("inet", "inet '192.168.0.1/24'", "192.168.0.1/24"),
            value("bit string", "b'101'", "101"),
            value("single bit", "b'1'", "1"),
            value("enum", "cast('planned' as $SAMPLE_ENUM)", "planned"),
        )

        private fun value(label: String, expression: String, expected: String): Arguments =
            Arguments.of(label, expression, expected)
    }
}
