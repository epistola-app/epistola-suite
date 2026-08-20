// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

export interface ExampleField {
  name: string;
  title?: string;
  description?: string;
  path?: readonly (string | number)[];
}

export interface ExampleValueProvider {
  string(field: ExampleField, constraints?: { format?: string }): string | undefined;
  number(field: ExampleField): number | undefined;
  boolean(field: ExampleField): boolean | undefined;
  arrayLength(
    field: ExampleField,
    constraints: { minimum: number; maximum?: number },
  ): number | undefined;
}
