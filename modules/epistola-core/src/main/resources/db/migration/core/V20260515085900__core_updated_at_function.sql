-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Shared trigger function. Stamps `updated_at = now()` on every UPDATE so the
-- audit timestamp is enforced by the database, not dependent on every DAO
-- remembering to `SET updated_at = NOW()`. Attached as a BEFORE UPDATE row
-- trigger to every table that has an `updated_at` column.
--
-- Created in the earliest baseline migration so any later table file can
-- reference it.
CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION set_updated_at() IS
    'BEFORE UPDATE trigger function: forces updated_at = now() on any row update.';
