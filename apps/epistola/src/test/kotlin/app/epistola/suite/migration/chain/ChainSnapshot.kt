// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.chain

import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.sql.Connection
import java.time.LocalDate

/**
 * What a migration chain leaves in a database, in a form two chains can be compared by: the schema
 * as `pg_dump` prints it, one statement per entry, and every row of every table, with what a run
 * generates (identities, keys, the time it ran) replaced by what stays the same from run to run.
 *
 * Two chains that convert the same seed the same way give the same snapshot, though they create their
 * columns in another order, name their generated ids differently and run at another time.
 */
object ChainSnapshot {

    private val mapper = JsonMapper.builder().build()

    /** `pg_dump -s` of [database], normalised: comments out, statements sorted, table columns sorted. */
    fun schema(postgres: PostgreSQLContainer, database: String): List<String> {
        val dump = postgres.execInContainer(
            "pg_dump", "--schema-only", "--no-owner", "--no-privileges", "--no-comments",
            "--username", postgres.username, "--dbname", database,
        )
        check(dump.exitCode == 0) { "pg_dump failed: ${dump.stderr}" }
        return statements(dump.stdout)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("SET ") || it.startsWith("SELECT pg_catalog.set_config") }
            .filterNot { "flyway_schema_history" in it }
            .map(::sortTableColumns)
            .map(::monthless)
            .sorted()
    }

    /**
     * Every row of every table but Flyway's own, one line each, `table: {row}`, sorted. A value a run
     * generates is replaced by a label made of the row it identifies (the rows naming it in their
     * primary key, described in turn a few levels deep), so a row and the rows that name it keep naming
     * each other without depending on what a run generated; a time within a day of now reads `<now>`.
     */
    fun data(connection: Connection): List<String> {
        val graph = Graph.read(connection)
        return graph.rows.flatMap { (table, rows) -> rows.map { "$table: ${graph.render(table, it, DEPTH)}" } }.sorted()
    }

    /** Rows the snapshot still cannot tell apart: two runs could pair them up differently. */
    fun ambiguousRows(connection: Connection): List<String> {
        val graph = Graph.read(connection)
        return graph.rows.flatMap { (table, rows) -> rows.map { "$table: ${graph.render(table, it, DEPTH)}" } }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
    }

    private const val DEPTH = 3

    private class Graph(
        val rows: Map<String, List<ObjectNode>>,
        private val generated: Set<String>,
        private val definers: Map<String, List<Pair<String, ObjectNode>>>,
    ) {
        fun render(table: String, row: ObjectNode, depth: Int): String {
            val out = mapper.createObjectNode()
            row.propertyNames().sorted().forEach { column ->
                val value = row.get(column)
                out.set(column, if ("$table.$column" in generated && value.isString) StringNode(label(value.asString(), depth)) else transform(value) { label(it, depth, inText = true) })
            }
            return mapper.writeValueAsString(out)
        }

        /**
         * A generated value's label. A uuid written by hand (version 0, as the seeds write theirs) that
         * no row generated is no run's, and stays as it is.
         */
        fun label(value: String, depth: Int, inText: Boolean = false): String {
            val defining = definers[value.lowercase()].orEmpty()
            if (inText && defining.isEmpty() && value[14] == '0') return value
            if (depth <= 0 || defining.isEmpty()) return "<id>"
            // A row's revision counts its edits; it says nothing of which row it is.
            val description = defining.map { (table, row) -> "$table${render(table, row.deepCopy().also { it.remove("revision") }, depth - 1)}" }.sorted().joinToString("|")
            return "@" + java.security.MessageDigest.getInstance("SHA-256").digest(description.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        }

        companion object {
            fun read(connection: Connection): Graph {
                val tables = tables(connection)
                val generated = generatedColumns(connection)
                val keys = primaryKeys(connection)
                val referencing = foreignKeyColumns(connection)
                val rows = tables.associateWith { rows(connection, it) }
                val definers = HashMap<String, MutableList<Pair<String, ObjectNode>>>()
                rows.forEach { (table, list) ->
                    // A row defines the ids its own key generates; a key column that references another
                    // table's row only repeats that row's id, and must not make its label depend on this one.
                    val keyColumns = keys[table].orEmpty().filter { "$table.$it" in generated && "$table.$it" !in referencing }
                    list.forEach { row ->
                        keyColumns.mapNotNull { row.get(it)?.takeIf { v -> v.isString }?.asString() }.forEach { value ->
                            definers.getOrPut(value.lowercase()) { mutableListOf() } += table to row
                        }
                    }
                }
                return Graph(rows, generated, definers)
            }
        }
    }

    private fun primaryKeys(connection: Connection): Map<String, List<String>> = connection.createStatement().use { st ->
        st.executeQuery(
            """
            SELECT tc.table_name, kcu.column_name FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema AND kcu.table_name = tc.table_name
            WHERE tc.table_schema = 'public' AND tc.constraint_type = 'PRIMARY KEY'
            """,
        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }.groupBy({ it.first }, { it.second })
    }

    /** `table.column` for every column of a foreign key. */
    private fun foreignKeyColumns(connection: Connection): Set<String> = connection.createStatement().use { st ->
        st.executeQuery(
            """
            SELECT c.relname || '.' || a.attname FROM pg_constraint k
            JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = ANY (k.conkey)
            WHERE k.contype = 'f' AND n.nspname = 'public'
            """,
        ).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun tables(connection: Connection): List<String> = connection.createStatement().use { st ->
        st.executeQuery(
            """
            SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition AND c.relname <> 'flyway_schema_history'
            ORDER BY c.relname
            """,
        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    /** `table.column` for every column whose values a run generates: uuids, sequence numbers, random keys. */
    private fun generatedColumns(connection: Connection): Set<String> = connection.createStatement().use { st ->
        st.executeQuery(
            """
            SELECT table_name || '.' || column_name FROM information_schema.columns
            WHERE table_schema = 'public'
              AND (data_type = 'uuid' OR column_default ILIKE '%uuid%' OR column_default ILIKE '%version_key%'
                   OR column_default ILIKE '%nextval%' OR column_default ILIKE '%random%')
            """,
        ).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun rows(connection: Connection, table: String): List<ObjectNode> = connection.createStatement().use { st ->
        st.executeQuery("SELECT to_jsonb(t)::text FROM \"$table\" t").use { rs ->
            buildList { while (rs.next()) add(mapper.readTree(rs.getString(1)) as ObjectNode) }
        }
    }

    private fun transform(node: JsonNode, label: (String) -> String): JsonNode = when {
        node.isString -> StringNode(replaceInText(node.asString(), label))
        node is ObjectNode -> mapper.createObjectNode().also { out -> node.propertyNames().sorted().forEach { out.set(it, transform(node.get(it), label)) } }
        node is ArrayNode -> mapper.createArrayNode().also { out -> node.forEach { out.add(transform(it, label)) } }
        else -> node
    }

    /** Statements of a dump: split at `;` outside quotes and dollar quotes, comment lines dropped. */
    internal fun statements(dump: String): List<String> {
        val text = dump.lines().filterNot { it.startsWith("--") || it.startsWith("\\restrict") || it.startsWith("\\unrestrict") }.joinToString("\n")
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        var dollarTag: String? = null
        var inQuote = false
        while (i < text.length) {
            val c = text[i]
            when {
                dollarTag != null -> if (text.startsWith(dollarTag, i)) {
                    current.append(dollarTag)
                    i += dollarTag.length
                    dollarTag = null
                    continue
                } else {
                    current.append(c)
                }

                inQuote -> {
                    current.append(c)
                    if (c == '\'') inQuote = false
                }

                c == '\'' -> {
                    current.append(c)
                    inQuote = true
                }

                c == '$' -> {
                    val end = text.indexOf('$', i + 1)
                    val tag = if (end > i) text.substring(i, end + 1) else null
                    if (tag != null && tag.drop(1).dropLast(1).all { it.isLetterOrDigit() || it == '_' }) {
                        current.append(tag)
                        i += tag.length
                        dollarTag = tag
                        continue
                    }
                    current.append(c)
                }

                c == ';' -> {
                    out += current.toString()
                    current.clear()
                }

                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    private val MONTHLY = Regex("""\b(event_log|generation_results)_\d{4}_\d{2}""")
    private val RANGE = Regex("""FOR VALUES FROM \('[^']*'\) TO \('[^']*'\)""")

    /** A monthly partition the schema's maintenance creates for the month it runs in, without the month. */
    internal fun monthless(statement: String): String {
        if (!MONTHLY.containsMatchIn(statement)) return statement
        return RANGE.replace(MONTHLY.replace(statement) { "${it.groupValues[1]}_<month>" }, "FOR VALUES FROM (<from>) TO (<to>)")
    }

    /** A `CREATE TABLE` with its column and constraint lines sorted: column order is not compared. */
    internal fun sortTableColumns(statement: String): String {
        if (!statement.startsWith("CREATE TABLE")) return statement
        val open = statement.indexOf("(\n")
        val close = statement.lastIndexOf("\n)")
        if (open < 0 || close < open) return statement
        val body = statement.substring(open + 2, close).lines().map { it.trim().removeSuffix(",") }.filter { it.isNotEmpty() }.sorted()
        return statement.substring(0, open + 2) + body.joinToString(",\n") { "    $it" } + statement.substring(close)
    }

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?([+-]\d{2}(:\d{2})?|Z)?""")

    /** [text] with a time within a day of now as `<now>`, and every uuid in it as [label] names it. */
    private fun replaceInText(text: String, label: (String) -> String): String {
        val today = LocalDate.now()
        val nearNow = setOf(today.minusDays(1), today, today.plusDays(1)).map { it.toString() }
        val noTimes = TIMESTAMP.replace(text) { if (it.value.take(10) in nearNow) "<now>" else it.value }
        return UUID.replace(noTimes) { label(it.value.lowercase()) }
    }
}
