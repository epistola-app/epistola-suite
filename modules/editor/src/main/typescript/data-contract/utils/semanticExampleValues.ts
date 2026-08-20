// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { base, en, Faker, nl } from '@faker-js/faker';
import type { ExampleField, ExampleValueProvider } from './exampleGeneration.js';

const DEFAULT_SEED = 20_260_820;

/**
 * Creates one generation-scoped provider. Related values share a fictional
 * profile, while a fixed seed keeps repeated completion predictable.
 */
export function createSemanticExampleValues(
  seed: number | string = DEFAULT_SEED,
): ExampleValueProvider {
  const faker = new Faker({ locale: [nl, en, base], seed: normalizeSeed(seed) });
  const person = {
    firstName: faker.person.firstName(),
    lastName: faker.person.lastName(),
  };
  const location = {
    street: faker.location.street(),
    city: faker.location.city(),
    postalCode: faker.location.zipCode(),
  };

  return {
    string(field) {
      const hint = semanticHint(field);

      if (matches(hint, ['gebruikersnaam', 'username', 'user name', 'loginnaam'])) {
        return faker.internet.username(person);
      }
      if (matches(hint, ['voornaam', 'first name', 'firstname', 'given name'])) {
        return person.firstName;
      }
      if (matches(hint, ['achternaam', 'last name', 'lastname', 'surname', 'family name'])) {
        return person.lastName;
      }
      if (matches(hint, ['email', 'e mail', 'emailadres', 'mailadres'])) {
        return faker.internet.exampleEmail(person);
      }
      if (matches(hint, ['telefoon', 'telefoonnummer', 'phone', 'mobile', 'mobiel'])) {
        return faker.phone.number({ style: 'international' });
      }
      if (matches(hint, ['postcode', 'postal code', 'zip code', 'zipcode'])) {
        return location.postalCode;
      }
      if (matches(hint, ['huisnummer', 'house number', 'building number'])) {
        return faker.location.buildingNumber();
      }
      if (matches(hint, ['straatnaam', 'straat', 'street name', 'street'])) {
        return location.street;
      }
      if (matches(hint, ['woonplaats', 'plaatsnaam', 'stad', 'city', 'town'])) {
        return location.city;
      }
      if (matches(hint, ['land', 'country'])) return 'Nederland';
      if (
        matches(hint, [
          'organisatie',
          'organization',
          'organisation',
          'bedrijfsnaam',
          'company',
          'gemeente',
          'municipality',
        ])
      ) {
        return faker.company.name();
      }
      if (matches(hint, ['iban', 'rekeningnummer', 'bank account'])) {
        return faker.finance.iban({ countryCode: 'NL' });
      }
      if (matches(hint, ['zaaknummer', 'case number', 'casenumber', 'dossiernummer'])) {
        return `ZAAK-${faker.number.int({ min: 100_000, max: 999_999 })}`;
      }
      if (matches(hint, ['referentienummer', 'reference number', 'reference'])) {
        return `REF-${faker.string.alphanumeric({ length: 8, casing: 'upper' })}`;
      }
      if (matches(hint, ['website', 'web site', 'url', 'homepage'])) {
        return faker.internet.url();
      }
      if (matches(hint, ['geboortedatum', 'birth date', 'birthdate', 'date of birth'])) {
        return faker.date
          .between({ from: '1970-01-01T00:00:00Z', to: '2000-12-31T00:00:00Z' })
          .toISOString()
          .slice(0, 10);
      }
      if (matches(hint, ['volledige naam', 'full name', 'fullname', 'contactnaam'])) {
        return `${person.firstName} ${person.lastName}`;
      }
      if (matches(hint, ['onderwerp', 'subject', 'titel', 'title'])) {
        return faker.lorem.words({ min: 3, max: 6 });
      }
      if (matches(hint, ['omschrijving', 'description', 'toelichting', 'comment', 'notes'])) {
        return faker.lorem.sentence();
      }
      if (matches(hint, ['naam', 'name'])) return `${person.firstName} ${person.lastName}`;

      return undefined;
    },

    number(field) {
      const hint = semanticHint(field);
      if (matches(hint, ['leeftijd', 'age'])) return 42;
      if (matches(hint, ['huisnummer', 'house number', 'building number'])) return 42;
      if (matches(hint, ['jaar', 'year'])) return 2026;
      if (matches(hint, ['bedrag', 'amount', 'prijs', 'price'])) return 125.5;
      if (matches(hint, ['aantal', 'quantity', 'count'])) return 3;
      return undefined;
    },

    boolean(field) {
      const hint = semanticHint(field);
      if (matches(hint, ['actief', 'active', 'enabled', 'goedgekeurd', 'approved'])) return true;
      return undefined;
    },
  };
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

function semanticHint(field: ExampleField): string {
  return [field.name, field.title, field.description]
    .filter((value): value is string => Boolean(value))
    .map(normalize)
    .join(' ');
}

function normalize(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function matches(hint: string, terms: string[]): boolean {
  return terms.some((term) => ` ${hint} `.includes(` ${term} `));
}
