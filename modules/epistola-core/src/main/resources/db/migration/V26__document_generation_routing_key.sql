-- v0.3 generation result collection — clients can pin where their results land via a
-- routing key. We persist the request's routing key (if supplied) so the emitter can use
-- it when the request reaches a terminal state. Null is allowed: emitter falls back to
-- the request id at emit time.
ALTER TABLE document_generation_requests
    ADD COLUMN routing_key TEXT;
