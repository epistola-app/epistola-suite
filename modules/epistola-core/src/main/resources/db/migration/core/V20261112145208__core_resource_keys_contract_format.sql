-- A resource key follows the contract's one rule (`CatalogKeys.RESOURCE`, wire v8): lowercase
-- letters and digits in hyphen-separated parts, and a leading digit is allowed. These domains still
-- required a leading letter, so a catalog whose template is keyed `2024-letter` could not be
-- installed, nor a document generated from it. The asset domain follows the contract already; the
-- lengths stay, as they bound keys stored before.
--
-- Only loosens: every value the old checks accepted, the new ones accept.
--
-- rollback-compatible: true

ALTER DOMAIN template_key DROP CONSTRAINT template_key_check;
ALTER DOMAIN template_key ADD CONSTRAINT template_key_check CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

ALTER DOMAIN variant_key DROP CONSTRAINT variant_key_check;
ALTER DOMAIN variant_key ADD CONSTRAINT variant_key_check CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

ALTER DOMAIN theme_key DROP CONSTRAINT theme_key_check;
ALTER DOMAIN theme_key ADD CONSTRAINT theme_key_check CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

ALTER DOMAIN stencil_key DROP CONSTRAINT stencil_key_check;
ALTER DOMAIN stencil_key ADD CONSTRAINT stencil_key_check CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

ALTER DOMAIN attribute_key DROP CONSTRAINT attribute_key_check;
ALTER DOMAIN attribute_key ADD CONSTRAINT attribute_key_check CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

ALTER DOMAIN code_list_key DROP CONSTRAINT code_list_key_check;
ALTER DOMAIN code_list_key ADD CONSTRAINT code_list_key_check CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

ALTER DOMAIN font_key DROP CONSTRAINT font_key_check;
ALTER DOMAIN font_key ADD CONSTRAINT font_key_check CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
