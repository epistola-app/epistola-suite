-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Cluster node registry for horizontal-scaling coordination.
--
-- Phase 1 only records node presence and capabilities. Later clustered timer
-- events, durable processes, cache invalidation fanout, and capability-aware
-- worker claiming all build on this table.

CREATE TABLE cluster_nodes (
    node_id       TEXT        PRIMARY KEY,
    capabilities JSONB       NOT NULL DEFAULT '[]'::jsonb,
    version      TEXT,
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL,
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_cluster_nodes_last_seen_at ON cluster_nodes(last_seen_at);

COMMENT ON TABLE cluster_nodes IS 'Heartbeat registry for active Epistola Suite runtime nodes and their capabilities.';
COMMENT ON COLUMN cluster_nodes.node_id IS 'Stable per-process node identifier from NodeIdentity.';
COMMENT ON COLUMN cluster_nodes.capabilities IS 'JSON array of advertised node capabilities, e.g. ["suite"] or future ["pdf-render"].';
COMMENT ON COLUMN cluster_nodes.version IS 'Running Suite build version when available.';
COMMENT ON COLUMN cluster_nodes.joined_at IS 'First time this node id appeared in the registry; preserved across heartbeats.';
COMMENT ON COLUMN cluster_nodes.last_seen_at IS 'Most recent heartbeat timestamp. Active-node queries filter on this column.';
COMMENT ON COLUMN cluster_nodes.metadata IS 'Small extensibility bag for future runtime metadata. Not for high-cardinality data.';
