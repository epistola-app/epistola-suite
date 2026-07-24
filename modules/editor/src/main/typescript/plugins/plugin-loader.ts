// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type {
  EditorFeatures,
  EditorFeatureFlag,
  EditorFeatureConfig,
} from '../engine/feature-flags.js';
import type { EditorPlugin } from './types.js';

export interface EditorPluginDescriptor {
  id: string;
  feature: EditorFeatureFlag;
  factoryExport: string;
  moduleUrl?: string;
  stylesheetUrl?: string;
  config?: unknown;
}

export interface EditorPluginFactoryContext {
  descriptor: EditorPluginDescriptor;
  feature: EditorFeatureConfig;
  config: unknown;
  csrfToken: () => string;
}

export interface EditorPluginLoadOptions {
  features?: EditorFeatures;
  csrfToken?: () => string;
  bundledModule?: Record<string, unknown>;
  importModule?: (url: string) => Promise<Record<string, unknown>>;
  document?: Document;
  logger?: Pick<Console, 'warn'>;
}

export type EditorPluginFactory = (
  context: EditorPluginFactoryContext,
) => EditorPlugin | null | undefined | Promise<EditorPlugin | null | undefined>;

export async function loadEditorPlugins(
  descriptors: EditorPluginDescriptor[] = [],
  options: EditorPluginLoadOptions = {},
): Promise<EditorPlugin[]> {
  const plugins: EditorPlugin[] = [];
  const features = options.features ?? {};
  const importModule = options.importModule ?? ((url: string) => import(/* @vite-ignore */ url));
  const logger = options.logger ?? console;

  for (const descriptor of descriptors) {
    const feature = features[descriptor.feature];
    if (feature?.enabled !== true) continue;

    try {
      loadStylesheet(descriptor, options.document ?? globalThis.document);
      const pluginModule = descriptor.moduleUrl
        ? await importModule(descriptor.moduleUrl)
        : (options.bundledModule ?? {});
      const factory = pluginModule[descriptor.factoryExport];
      if (typeof factory !== 'function') {
        throw new Error(`Plugin factory export '${descriptor.factoryExport}' was not found`);
      }

      const plugin = await (factory as EditorPluginFactory)({
        descriptor,
        feature,
        config: descriptor.config,
        csrfToken: options.csrfToken ?? (() => ''),
      });
      if (plugin) plugins.push(plugin);
    } catch (e) {
      logger.warn(`Editor plugin '${descriptor.id}' failed to load:`, e);
    }
  }

  return plugins;
}

function loadStylesheet(
  descriptor: EditorPluginDescriptor,
  documentRef: Document | undefined,
): void {
  if (!descriptor.stylesheetUrl || !documentRef) return;
  const existing = documentRef.head.querySelector(
    `link[data-editor-plugin-style="${cssEscape(descriptor.id)}"]`,
  );
  if (existing) return;

  const link = documentRef.createElement('link');
  link.rel = 'stylesheet';
  link.href = descriptor.stylesheetUrl;
  link.dataset.editorPluginStyle = descriptor.id;
  documentRef.head.appendChild(link);
}

function cssEscape(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}
