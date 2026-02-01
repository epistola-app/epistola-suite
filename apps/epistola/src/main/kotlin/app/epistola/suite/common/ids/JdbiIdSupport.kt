package app.epistola.suite.common.ids

import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.mapper.ColumnMapperFactory
import java.lang.reflect.Type
import java.sql.Types
import java.util.Optional
import java.util.UUID

/**
 * JDBI ColumnMapperFactory that maps database UUID columns to typed entity IDs.
 * Handles all 8 entity ID types defined in [EntityId].
 */
class EntityIdColumnMapperFactory : ColumnMapperFactory {
    private val idConstructors: Map<Class<*>, (UUID) -> EntityId<*>> =
        mapOf(
            TenantId::class.java to ::TenantId,
            TemplateId::class.java to ::TemplateId,
            VariantId::class.java to ::VariantId,
            VersionId::class.java to ::VersionId,
            EnvironmentId::class.java to ::EnvironmentId,
            DocumentId::class.java to ::DocumentId,
            GenerationRequestId::class.java to ::GenerationRequestId,
            GenerationItemId::class.java to ::GenerationItemId,
        )

    override fun build(
        type: Type,
        config: ConfigRegistry,
    ): Optional<ColumnMapper<*>> {
        val rawClass = type as? Class<*> ?: return Optional.empty()
        val constructor = idConstructors[rawClass] ?: return Optional.empty()
        return Optional.of(
            ColumnMapper { rs, col, _ ->
                rs.getObject(col, UUID::class.java)?.let { constructor(it) }
            },
        )
    }
}

/**
 * JDBI ArgumentFactory that binds typed entity IDs as UUID arguments for SQL statements.
 * Works with all [EntityId] implementations.
 */
class EntityIdArgumentFactory : AbstractArgumentFactory<EntityId<*>>(Types.OTHER) {
    override fun build(
        value: EntityId<*>,
        config: ConfigRegistry,
    ): Argument = Argument { pos, stmt, _ -> stmt.setObject(pos, value.value) }
}
