package cz.lubos.mcphub.catalog

data class TableDescription(
    val owner: String,
    val table: String,
    val columns: List<ColumnDescription>,
    val constraints: List<ConstraintDescription>,
    val indexes: List<IndexDescription>,
)

/** [type] is already rendered the way it reads in DDL, for example `VARCHAR2(400)`. */
data class ColumnDescription(
    val name: String,
    val type: String,
    val nullable: Boolean,
)

data class ConstraintDescription(
    val name: String,
    val type: String,
    val columns: List<String>,
    val referencedTable: String?,
)

data class IndexDescription(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

enum class SchemaObjectKind {
    TABLE,
    VIEW,
    COLUMN,
    PROGRAM,
}

/**
 * One hit of a schema search. [name] is the table for a table, `TABLE.COLUMN` for a column and
 * the object name for a program; [detail] carries the data type or the object type.
 */
data class SchemaMatch(
    val kind: SchemaObjectKind,
    val owner: String,
    val name: String,
    val detail: String,
)

/**
 * A slice of one object's source. [totalLines] is the whole object, so a caller knows what it
 * has not seen yet and can ask for the next range.
 */
data class ObjectSource(
    val owner: String,
    val name: String,
    val type: String,
    val totalLines: Int,
    val fromLine: Int,
    val toLine: Int,
    val hasMore: Boolean,
    val source: String,
)

/**
 * [sqlId] and [childNumber] identify the cursor an Oracle plan was read from. PostgreSQL plans the
 * statement it is given and has no cursor to point at, so it leaves them null.
 */
data class QueryPlan(
    val sqlId: String?,
    val childNumber: Int?,
    val sqlText: String,
    val plan: String,
)
