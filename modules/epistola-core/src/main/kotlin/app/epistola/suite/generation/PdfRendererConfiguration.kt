// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.suite.templates.validation.NodeParameterSchemaProviderRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the generation module's [DirectPdfRenderer] with the Spring-managed
 * [NodeParameterSchemaProviderRegistry] so render-time stencil parameter
 * resolution uses the per-node-type providers (today: stencil snapshot;
 * future: static-parametrised components).
 *
 * Without this bean, [DirectPdfRenderer] would fall back to its no-op default
 * (always returns null) and parametrised components would render with empty
 * scopes.
 */
@Configuration
class PdfRendererConfiguration {

    @Bean
    fun directPdfRenderer(
        schemaProviderRegistry: NodeParameterSchemaProviderRegistry,
    ): DirectPdfRenderer = DirectPdfRenderer(
        parameterSchemaProvider = { node, document -> schemaProviderRegistry.resolve(node, document) },
    )
}
