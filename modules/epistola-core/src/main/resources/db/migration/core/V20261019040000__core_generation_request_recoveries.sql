-- How often a generation request was taken back from a node that stopped while rendering it (crashed,
-- or stopped responding). A render that brings its node down would otherwise be handed to the next
-- node, and the next, forever; after a few recoveries it is failed with the reason instead.
ALTER TABLE document_generation_requests ADD COLUMN recoveries INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN document_generation_requests.recoveries IS
    'Times the request was taken back from a node that stopped while rendering it (stale-job recovery); at the limit it fails instead of rendering again';
