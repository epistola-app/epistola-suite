// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

export type ResourceType =
  | 'asset'
  | 'codeList'
  | 'font'
  | 'attribute'
  | 'theme'
  | 'stencil'
  | 'template';
export type ReferenceSemantics = 'runtime' | 'authoring' | 'provenance';
export type Resolution = 'resolved' | 'missing' | 'ambiguous';

export interface ResourceNode {
  id: string;
  type: ResourceType;
  catalogKey: string;
  key: string;
  name: string;
  catalogName: string;
  catalogType: string;
}

export interface ResourceAddress {
  id: string;
  type: ResourceType;
  catalogKey: string;
  key: string;
}

export interface ResourceEdge {
  id: string;
  source: string;
  target: string | null;
  targetSelector: Omit<ResourceAddress, 'id'>;
  targetCandidates: ResourceAddress[];
  kind: string;
  semantics: ReferenceSemantics;
  qualification: string;
  resolution: Resolution;
  resolvedViaAlias: boolean;
  evidenceCount: number;
}

export interface ResourceMovePreview {
  source: ResourceAddress;
  target: ResourceAddress;
  mutableRewriteCount: number;
  immutableReferenceCount: number;
  blockers: Array<{ code: string; message: string }>;
  planFingerprint: string;
  executable: boolean;
}

export interface SubgraphResponse {
  focus: ResourceAddress;
  nodes: ResourceNode[];
  edges: ResourceEdge[];
}

export interface ReferenceEvidence {
  owner: string;
  lifecycle: 'LIVE' | 'HISTORICAL';
  status?: string;
  version?: number;
  location: string;
  pinnedVersion?: number;
}

export interface EvidenceResponse {
  edge: ResourceEdge;
  items: ReferenceEvidence[];
  page: number;
  total: number;
  totalPages: number;
}

export const RESOURCE_TYPES: ReadonlyArray<{ value: ResourceType; label: string }> = [
  { value: 'template', label: 'Templates' },
  { value: 'theme', label: 'Themes' },
  { value: 'stencil', label: 'Stencils' },
  { value: 'attribute', label: 'Attributes' },
  { value: 'codeList', label: 'Code lists' },
  { value: 'font', label: 'Fonts' },
  { value: 'asset', label: 'Assets' },
];

export function displayType(type: ResourceType): string {
  return RESOURCE_TYPES.find((entry) => entry.value === type)?.label.replace(/s$/, '') ?? type;
}
