// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { base, en, Faker, nl } from '@faker-js/faker';
import type { ExampleField } from './example-value-provider.js';

const DEFAULT_SEED = 20_260_820;

export interface PersonProfile {
  firstName: string;
  lastName: string;
}

interface LocationProfile {
  street: string;
  city: string;
  postalCode: string;
}

export interface SemanticExampleContext {
  faker: Faker;
  personFor(field: ExampleField, role?: string): PersonProfile;
  locationFor(field: ExampleField): LocationProfile;
  nextExampleEmail(person: PersonProfile): string;
  nextFallback(field: ExampleField): string;
}

export function createSemanticExampleContext(
  seed: number | string = DEFAULT_SEED,
): SemanticExampleContext {
  const faker = new Faker({ locale: [nl, en, base], seed: normalizeSeed(seed) });
  const people = new Map<string, PersonProfile>();
  const locations = new Map<string, LocationProfile>();
  const usedNames = new Set<string>();
  const fallbackSequences = new Map<string, number>();
  let emailSequence = 0;

  return {
    faker,

    personFor(field, role = 'person') {
      const key = `${role}:${scopeKey(field)}`;
      const existing = people.get(key);
      if (existing) return existing;

      const person = uniquePerson(faker, usedNames, people.size + 1);
      usedNames.add(fullName(person));
      people.set(key, person);
      return person;
    },

    locationFor(field) {
      const key = scopeKey(field);
      const existing = locations.get(key);
      if (existing) return existing;

      const location = {
        street: faker.location.street(),
        city: faker.location.city(),
        postalCode: faker.location.zipCode(),
      };
      locations.set(key, location);
      return location;
    },

    nextExampleEmail(person) {
      emailSequence += 1;
      const [localPart, domain = 'example.com'] = faker.internet.exampleEmail(person).split('@');
      return `${localPart}+${emailSequence}@${domain}`;
    },

    nextFallback(field) {
      const label = humanize(field.title ?? field.name);
      const sequence = nearestArrayIndex(field) ?? (fallbackSequences.get(label) ?? 0) + 1;
      fallbackSequences.set(label, sequence);
      return `Example ${label} ${sequence}`;
    },
  };
}

export function fullName(person: PersonProfile): string {
  return `${person.firstName} ${person.lastName}`;
}

function uniquePerson(
  faker: Faker,
  usedNames: Set<string>,
  fallbackSequence: number,
): PersonProfile {
  let candidate: PersonProfile = {
    firstName: faker.person.firstName(),
    lastName: faker.person.lastName(),
  };
  for (let attempt = 0; attempt < 10 && usedNames.has(fullName(candidate)); attempt += 1) {
    candidate = {
      firstName: faker.person.firstName(),
      lastName: faker.person.lastName(),
    };
  }
  if (usedNames.has(fullName(candidate))) {
    candidate.lastName = `${candidate.lastName} ${fallbackSequence}`;
  }
  return candidate;
}

function scopeKey(field: ExampleField): string {
  const path = field.path ?? [];
  const scope = typeof path.at(-1) === 'number' ? path : path.slice(0, -1);
  return JSON.stringify(scope);
}

function nearestArrayIndex(field: ExampleField): number | undefined {
  const index = field.path?.findLast((segment): segment is number => typeof segment === 'number');
  return index === undefined ? undefined : index + 1;
}

function normalizeSeed(seed: number | string): number {
  if (typeof seed === 'number') return seed;

  let hash = 2_166_136_261;
  for (const character of seed) {
    hash ^= character.codePointAt(0) ?? 0;
    hash = Math.imul(hash, 16_777_619);
  }
  return hash >>> 0;
}

function humanize(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}
