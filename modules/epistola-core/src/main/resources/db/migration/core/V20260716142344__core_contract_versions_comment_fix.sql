-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Documentation-only: corrects two inverted column comments on contract_versions.
--
-- The original comments (V20260515090800) claimed `schema` defined the contract and that
-- `data_model` was merely "informational". That is backwards: every validation path
-- (DocumentGenerationExecutor, PreviewDocument, PreviewVariant, GenerationService, the REST
-- validate endpoint) reads `data_model`, and nothing validates against `schema`.
--
-- No data or structure is touched.

COMMENT ON COLUMN contract_versions.data_model IS
    'JSON Schema describing the expected input structure. This is the schema submitted data is validated against on every path (generation, preview, REST validate). NULL means no validation.';

COMMENT ON COLUMN contract_versions.schema IS
    'Vestigial second schema: written on create/update and surfaced over MCP, but no validation path reads it. Despite the name, NOT the schema data is checked against. Pending a decision to use or drop it.';
