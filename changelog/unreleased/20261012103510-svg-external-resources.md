---
type: fix
scopes: [generation, assets, catalog]
title: SVG images render from their own content only, and unsafe SVGs are refused on upload.
---

An SVG image could make the PDF renderer request URLs and embed image files from the server's disk through its `href`s, stylesheets and fonts. Rendering now never fetches or reads anything an SVG references; inline `data:` images, `#fragment` references, shapes and text still render. Uploading an SVG through the web UI, the REST API, MCP or a catalog import now refuses one with a DOCTYPE, scripts, event handlers, external links or CSS that loads other resources. SVG images that are already stored keep rendering, without their external references. Served SVG images carry a restrictive Content-Security-Policy.
