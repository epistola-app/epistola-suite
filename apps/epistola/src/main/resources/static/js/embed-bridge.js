// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Embedding / postMessage bridge (docs/embedding.md, ADR 0015). Demo-mode only —
// this file is loaded conditionally, gated server-side by epistola.embedding.enabled
// (fragments/htmx.html), so none of this exists in a non-embedding deployment.
//
// Protocol (both directions always carry `source` so messages are recognizable):
//   host  -> suite: { source: 'epistola-host',  type: 'navigate', resource: {resourceType, tenantId, catalogKey, key} }
//   suite -> host:  { source: 'epistola-suite', type: 'navigated', path, resource: {...} | null }
//   suite -> host:  { source: 'epistola-suite', type: 'resource-changed', resource: {...}, operation: 'create'|'update'|'delete' }
//
// Security: the host can only ever hand over a typed resource identity, never a
// URL — resolveResourcePath() below is a fixed, closed lookup table, and the
// resulting navigation still goes through the destination page's normal
// server-side auth/existence checks (htmx.ajax hits the real route), exactly as
// an in-app link click would. postMessage always targets an explicit allowlisted
// origin, never "*"; every inbound message is checked against the same allowlist
// plus event.source === window.parent before anything is acted on.
//
// resource-changed detection is entirely client-side: template/theme/stencil
// mutations follow one uniform URL convention (POST the collection root to
// create, PATCH the resource path to update, POST ".../delete" to delete —
// confirmed against DocumentTemplateRoutes/ThemeRoutes/StencilRoutes), so a
// generic htmx:afterRequest listener below can classify every create/update/
// delete without any backend involvement — no per-handler Kotlin call sites to
// keep in sync as resource types/handlers are added. The two mutations that
// aren't observable this way (a raw fetch() that bypasses htmx.js entirely, and
// a genuine full-page redirect with no client-side request at all) are each
// handled by one small, explicit exception below.
(function () {
  const configIsland = document.getElementById('epistola-embed-config');
  if (!configIsland) return;

  let allowedOrigins;
  try {
    allowedOrigins = JSON.parse(configIsland.textContent);
  } catch (e) {
    return;
  }
  if (!Array.isArray(allowedOrigins) || allowedOrigins.length === 0) return;

  // A single configured origin is the fixed postMessage target for the whole
  // session. With more than one allowed, fall back to document.referrer on
  // first load, then let the host's own first verified message settle it.
  let targetOrigin = allowedOrigins.length === 1 ? allowedOrigins[0] : null;
  if (!targetOrigin && document.referrer) {
    try {
      const referrerOrigin = new URL(document.referrer).origin;
      if (allowedOrigins.indexOf(referrerOrigin) !== -1) targetOrigin = referrerOrigin;
    } catch (e) {
      // malformed referrer — leave targetOrigin unresolved
    }
  }

  function postToHost(message) {
    if (!targetOrigin) return;
    window.parent.postMessage(message, targetOrigin);
  }

  const SLUG_PATTERN = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;
  function isValidSlug(value) {
    return typeof value === 'string' && SLUG_PATTERN.test(value);
  }

  const RESOURCE_PATH_SEGMENT = { template: 'templates', theme: 'themes', stencil: 'stencils' };
  const RESOURCE_TYPE_BY_SEGMENT = { templates: 'template', themes: 'theme', stencils: 'stencil' };
  const RESOURCE_URL_PATTERN = /^\/tenants\/([^/]+)\/(templates|themes|stencils)(\/.*)?$/;

  // The only way a resource identity becomes a URL: a closed lookup of the
  // three known detail-page shapes. The host cannot supply anything else.
  function resolveResourcePath(resource) {
    if (!resource) return null;
    const segment = RESOURCE_PATH_SEGMENT[resource.resourceType];
    if (!segment) return null;
    if (
      !isValidSlug(resource.tenantId) ||
      !isValidSlug(resource.catalogKey) ||
      !isValidSlug(resource.key)
    )
      return null;
    return (
      '/tenants/' +
      encodeURIComponent(resource.tenantId) +
      '/' +
      segment +
      '/' +
      encodeURIComponent(resource.catalogKey) +
      '/' +
      encodeURIComponent(resource.key)
    );
  }

  // The reverse direction: a URL path (a request path, or a create response's
  // HX-Location/HX-Redirect target) back into {tenantId, resourceType, rest[]},
  // where `rest` is whatever path segments follow the collection root (e.g. []
  // for a create POST, [catalogKey, key] for a plain resource path, or
  // [catalogKey, key, 'delete'] for a delete POST).
  function parseResourcePath(path) {
    const match = RESOURCE_URL_PATTERN.exec(path);
    if (!match) return null;
    return {
      tenantId: match[1],
      resourceType: RESOURCE_TYPE_BY_SEGMENT[match[2]],
      rest: (match[3] || '').split('/').filter(Boolean),
    };
  }

  window.addEventListener('message', function (event) {
    if (event.source !== window.parent) return;
    if (allowedOrigins.indexOf(event.origin) === -1) return;
    // The host's own first verified message settles targetOrigin when more
    // than one origin is configured (see above).
    if (!targetOrigin) targetOrigin = event.origin;

    const data = event.data;
    if (!data || data.source !== 'epistola-host' || data.type !== 'navigate') return;

    const path = resolveResourcePath(data.resource);
    if (!path) return;

    // htmx's own ajax navigation path — the same route an in-app <a> click
    // takes — so the destination's normal permission/existence checks and
    // body-hosted boot scripts run exactly as usual. There is no separate
    // "trusted host navigation" path that could diverge or skip a check.
    // htmx.ajax() has no pushUrl option of its own (that's an hx-push-url
    // *attribute* read from a triggering element, not a JS API option), so
    // the history entry is pushed manually once the swap completes.
    if (typeof htmx !== 'undefined') {
      htmx.ajax('GET', path, { target: 'body', swap: 'innerHTML' }).then(function () {
        if (location.pathname !== path) history.pushState(null, '', path);
      });
    }
  });

  let lastNotifiedPath = null;
  function notifyNavigated() {
    const path = location.pathname + location.search;
    if (path === lastNotifiedPath) return;
    lastNotifiedPath = path;
    const island = document.getElementById('epistola-current-resource');
    let resource = null;
    if (island) {
      try {
        resource = JSON.parse(island.textContent);
      } catch (e) {
        resource = null;
      }
    }
    postToHost({ source: 'epistola-suite', type: 'navigated', path: path, resource: resource });
  }
  document.addEventListener('htmx:load', notifyNavigated);
  window.addEventListener('popstate', notifyNavigated);

  function notifyResourceChanged(resource, operation) {
    postToHost({
      source: 'epistola-suite',
      type: 'resource-changed',
      resource: resource,
      operation: operation,
    });
  }

  // Classifies every real htmx create/update/delete from the request (and, for
  // create, the response) alone — see the file header for why this needs no
  // backend involvement. A PATCH to a resource path is an update; a POST whose
  // path ends in "/delete" is a delete; a POST to the bare collection root is a
  // create, whose identity comes from the same HX-Location/HX-Redirect header
  // that already drives the post-create navigation (set for unrelated,
  // pre-existing UX reasons — dialogLocation/dialogRedirect in HtmxDsl.kt).
  document.addEventListener('htmx:afterRequest', function (event) {
    // Not event.detail.successful: htmx computes that relative to whether a
    // swap occurred, and HX-Location (create's soft-navigate response) has no
    // swap at all — successful is left undefined for it even on a real 200.
    // The XHR status is the actual, direct signal.
    const xhr = event.detail.xhr;
    if (!xhr || xhr.status < 200 || xhr.status >= 300) return;
    const verb = (
      (event.detail.requestConfig && event.detail.requestConfig.verb) ||
      ''
    ).toUpperCase();
    if (verb !== 'POST' && verb !== 'PATCH') return;

    const requestPath = event.detail.pathInfo && event.detail.pathInfo.requestPath;
    if (!requestPath) return;
    const parsed = parseResourcePath(requestPath);
    if (!parsed) return;

    if (verb === 'PATCH' && parsed.rest.length >= 2) {
      notifyResourceChanged(
        {
          resourceType: parsed.resourceType,
          tenantId: parsed.tenantId,
          catalogKey: parsed.rest[0],
          key: parsed.rest[1],
        },
        'update',
      );
      return;
    }

    if (verb === 'POST' && parsed.rest.length === 3 && parsed.rest[2] === 'delete') {
      notifyResourceChanged(
        {
          resourceType: parsed.resourceType,
          tenantId: parsed.tenantId,
          catalogKey: parsed.rest[0],
          key: parsed.rest[1],
        },
        'delete',
      );
      return;
    }

    if (verb === 'POST' && parsed.rest.length === 0) {
      const createdUrl =
        xhr.getResponseHeader('HX-Location') || xhr.getResponseHeader('HX-Redirect');
      if (!createdUrl) return;
      const createdParsed = parseResourcePath(createdUrl.split('?')[0]);
      if (createdParsed && createdParsed.rest.length === 2) {
        notifyResourceChanged(
          {
            resourceType: createdParsed.resourceType,
            tenantId: createdParsed.tenantId,
            catalogKey: createdParsed.rest[0],
            key: createdParsed.rest[1],
          },
          'create',
        );
      }
    }
  });

  // Raw fetch() mutations bypass htmx.js entirely, so the listener above never
  // sees them — those call sites invoke this directly after a successful
  // response (e.g. theme-editor-boot.js's onSave).
  window.epistolaEmbedBridge = { notifyResourceChanged: notifyResourceChanged };

  // The one full-page-redirect delete (DocumentTemplateHandler.delete) can't
  // carry a response header across a browser-followed redirect, so it appends
  // a one-shot flash query param to the redirect target instead. Read it once,
  // then scrub it so a refresh doesn't re-fire the notification.
  (function consumeFlashResourceDeleted() {
    const params = new URLSearchParams(location.search);
    const flash = params.get('resourceDeleted');
    if (!flash) return;
    const parts = flash.split(':');
    const tenantMatch = /^\/tenants\/([^/]+)\//.exec(location.pathname);
    if (parts.length === 3 && tenantMatch) {
      notifyResourceChanged(
        { resourceType: parts[0], tenantId: tenantMatch[1], catalogKey: parts[1], key: parts[2] },
        'delete',
      );
    }
    params.delete('resourceDeleted');
    const nextSearch = params.toString();
    history.replaceState(
      history.state,
      '',
      location.pathname + (nextSearch ? '?' + nextSearch : '') + location.hash,
    );
  })();

  notifyNavigated();
})();
