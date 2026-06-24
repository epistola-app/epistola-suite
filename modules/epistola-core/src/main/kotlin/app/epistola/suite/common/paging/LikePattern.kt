package app.epistola.suite.common.paging

/**
 * Build a case-insensitive "contains" pattern for binding into a `col ILIKE :term` clause.
 *
 * The user's term is treated as a *literal*: its LIKE metacharacters (`%`, `_`, and the
 * escape `\` itself) are escaped so a search for `svc_prod` matches the literal underscore,
 * not "any character". Bind the result to the parameter and use a plain `ILIKE :term` — no
 * explicit `ESCAPE` clause, because Postgres' default LIKE escape is already `\` (a paired
 * `ESCAPE '\'` also confuses JDBI's `:name` string-literal tracking across multiple ILIKEs).
 *
 * One shared primitive so every list query escapes search input identically; see ADR 0007.
 */
fun ilikeContains(term: String): String = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
