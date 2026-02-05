-- V10: Create Spring Session Tables
--
-- These tables are used by Spring Session JDBC to store HTTP sessions in the database.
-- This enables session sharing across multiple application instances.
--
-- Schema based on: https://github.com/spring-projects/spring-session

-- ============================================================================
-- SPRING_SESSION TABLE
-- ============================================================================

CREATE TABLE spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

COMMENT ON TABLE spring_session IS 'HTTP sessions for Spring Session JDBC';
COMMENT ON COLUMN spring_session.primary_id IS 'Internal primary key';
COMMENT ON COLUMN spring_session.session_id IS 'Session ID sent to client as cookie';
COMMENT ON COLUMN spring_session.principal_name IS 'Username of authenticated user';

-- ============================================================================
-- SPRING_SESSION_ATTRIBUTES TABLE
-- ============================================================================

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session(primary_id) ON DELETE CASCADE
);

COMMENT ON TABLE spring_session_attributes IS 'Session attributes (serialized Java objects)';
