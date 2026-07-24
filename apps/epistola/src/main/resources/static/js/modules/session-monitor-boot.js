// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Boot shim for the session monitor. Included by fragments/footer.html for
// authenticated users. Module scripts are evaluated once per URL by the
// browser's module map, so re-insertion via hx-boost body swaps is a no-op.
import { initSessionMonitor } from './session-monitor.js';

initSessionMonitor();
