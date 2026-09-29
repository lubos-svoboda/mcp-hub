package cz.lubos.mcphub.query

import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.support.OracleTestDatabase
import cz.lubos.mcphub.support.OracleTestDatabase.CZECH_LABEL
import cz.lubos.mcphub.support.OracleTestDatabase.ENVIRONMENT_NAME
import cz.lubos.mcphub.support.OracleTestDatabase.SAMPLE_TABLE
import cz.lubos.mcphub.support.OracleTestDatabase.SAMPLE_VIEW
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/** Every value is written as a literal, so no table is needed and the query shows the exact input. */
@Tag("integration")
class OracleColumnTypesIT {

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    fun `a column type comes back in its readable form`(label: String, sql: String, expected: String) {
        assertThat(runQuery(sql).rows.single().single()).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "varchar2(10)", "nvarchar2(10)", "char(5)", "nchar(5)",
            "number", "number(38,10)", "float", "binary_float", "binary_double",
            "date", "timestamp", "timestamp(9)", "timestamp with time zone", "timestamp with local time zone",
            "interval day to second", "interval year to month",
            "raw(10)", "boolean", "json", "rowid",
        ],
    )
    fun `a null of a column type stays null`(type: String) {
        assertThat(runQuery("select cast(null as $type) as v from dual").rows.single().single()).isNull()
    }

    /** Oracle refuses a cast to these types, so the null comes from a function or an empty subquery. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["to_clob(null)", "to_nclob(null)", "to_blob(null)", "(select xmltype('<a/>') from dual where 1 = 0)"])
    fun `a null of a type that cannot be cast to stays null`(expression: String) {
        assertThat(runQuery("select $expression as v from dual").rows.single().single()).isNull()
    }

    /** LONG cannot be cast to, so the null comes from a dictionary column of that type. */
    @Test
    fun `a null LONG stays null`() {
        val result = runQuery(
            "select data_default from user_tab_columns where table_name = '$SAMPLE_TABLE' and column_name = 'LABEL'",
        )

        assertThat(result.rows.single().single()).isNull()
    }

    private fun runQuery(sql: String): QueryResult =
        EnvironmentRegistry(OracleTestDatabase.hubProperties()).use { registry ->
            QueryRunner(ReadOnlySession(), ResultMapper()).run(requireNotNull(registry.find(ENVIRONMENT_NAME)), sql)
        }

    private companion object {

        @JvmStatic
        fun values(): List<Arguments> = listOf(
            fromDual("varchar2 with diacritics", "cast('$CZECH_LABEL' as varchar2(400))", CZECH_LABEL),
            fromDual("nvarchar2 with diacritics", "cast(n'$CZECH_LABEL' as nvarchar2(400))", CZECH_LABEL),
            fromDual("char keeps its padding", "cast('ab' as char(5))", "ab   "),
            fromDual("nchar keeps its padding", "cast(n'áb' as nchar(5))", "áb   "),
            fromDual("nclob with diacritics", "to_nclob(n'$CZECH_LABEL')", CZECH_LABEL),
            arguments(
                "long",
                "select text from user_views where view_name = '$SAMPLE_VIEW'",
                "select id, label from $SAMPLE_TABLE where label is not null",
            ),
            fromDual("number without precision", "cast(123.45 as number)", "123.45"),
            fromDual("number without an exponent", "cast(1e30 as number)", "1000000000000000000000000000000"),
            fromDual("negative number", "cast(-0.5 as number(10,2))", "-0.5"),
            fromDual("float", "cast(0.5 as float)", "0.5"),
            fromDual("binary_float", "cast(0.5 as binary_float)", "0.5"),
            fromDual("binary_double", "cast(0.1 as binary_double)", "0.1"),
            fromDual("binary_double NaN", "binary_double_nan", "NaN"),
            fromDual("binary_double infinity", "binary_double_infinity", "Infinity"),
            fromDual("date at midnight", "date '2026-03-15'", "2026-03-15T00:00:00"),
            fromDual("date with time", "to_date('2026-03-15 08:00:45', 'yyyy-mm-dd hh24:mi:ss')", "2026-03-15T08:00:45"),
            fromDual("timestamp with nanoseconds", "timestamp '2026-03-15 08:00:45.123456789'", "2026-03-15T08:00:45.123456789"),
            fromDual(
                "timestamp with time zone offset",
                "timestamp '2026-03-15 08:00:45.123 +02:00'",
                "2026-03-15T08:00:45.123+02:00",
            ),
            fromDual(
                "timestamp with time zone region",
                "timestamp '2026-03-15 08:00:45 Europe/Prague'",
                "2026-03-15T08:00:45+01:00",
            ),
            fromDual(
                "timestamp with local time zone",
                "cast(timestamp '2026-03-15 08:00:45 +00:00' as timestamp with local time zone)",
                "2026-03-15T08:00:45Z",
            ),
            fromDual("interval day to second", "interval '1 02:03:04.5' day to second", "+01 02:03:04.500000"),
            fromDual(
                "interval day to second with its own precision",
                "interval '123 02:03:04.5' day(3) to second(2)",
                "+123 02:03:04.50",
            ),
            fromDual("negative interval day to second", "interval '-1 02:03:04.5' day to second", "-01 02:03:04.500000"),
            fromDual("interval year to month", "interval '2-3' year to month", "+02-03"),
            fromDual("negative interval year to month", "interval '-2-3' year to month", "-02-03"),
            fromDual("raw", "hextoraw('0102')", "<binary, 2 bytes>"),
            fromDual("blob", "to_blob(hextoraw('010203'))", "<binary, 3 bytes>"),
            fromDual("boolean", "true", "true"),
            fromDual("json", "json('{\"a\":1}')", "{\"a\":1}"),
            fromDual("xmltype", "xmltype('<a>1</a>')", "<a>1</a>"),
            fromDual("rowid", "chartorowid('AAAR3sAAEAAAACXAAA')", "AAAR3sAAEAAAACXAAA"),
        )

        private fun fromDual(label: String, expression: String, expected: String): Arguments =
            arguments(label, "select $expression as v from dual", expected)

        private fun arguments(label: String, sql: String, expected: String): Arguments = Arguments.of(label, sql, expected)
    }
}
