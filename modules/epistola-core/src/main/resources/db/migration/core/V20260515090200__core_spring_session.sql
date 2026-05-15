-- Spring Session JDBC tables for HTTP session storage.
-- Enables session sharing across multiple application instances.
--
-- Schema based on: https://github.com/spring-projects/spring-session

CREATE TABLE web_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT web_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX web_session_ix1 ON web_session (session_id);
CREATE INDEX web_session_ix2 ON web_session (expiry_time);
CREATE INDEX web_session_ix3 ON web_session (principal_name);

COMMENT ON TABLE web_session IS 'HTTP sessions managed by Spring Session JDBC';
COMMENT ON COLUMN web_session.primary_id IS 'Internal primary key (UUID)';
COMMENT ON COLUMN web_session.session_id IS 'Session ID sent to client as cookie';
COMMENT ON COLUMN web_session.creation_time IS 'Session creation time (epoch millis)';
COMMENT ON COLUMN web_session.last_access_time IS 'Last request time (epoch millis)';
COMMENT ON COLUMN web_session.max_inactive_interval IS 'Session timeout in seconds';
COMMENT ON COLUMN web_session.expiry_time IS 'When the session expires (epoch millis)';
COMMENT ON COLUMN web_session.principal_name IS 'Username of authenticated user';

CREATE TABLE web_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT web_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT web_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES web_session(primary_id) ON DELETE CASCADE
);

COMMENT ON TABLE web_session_attributes IS 'Session attributes stored as serialized Java objects';
COMMENT ON COLUMN web_session_attributes.session_primary_id IS 'FK to web_session.primary_id';
COMMENT ON COLUMN web_session_attributes.attribute_name IS 'Attribute key within the session';
COMMENT ON COLUMN web_session_attributes.attribute_bytes IS 'Serialized attribute value';
