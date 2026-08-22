// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { ExampleField, ExampleValueProvider } from './example-value-provider.js';
import { createSemanticExampleContext, fullName } from './semantic-example-context.js';

/**
 * Creates one generation-scoped provider. Related values share a fictional
 * profile, while a fixed seed keeps repeated completion predictable.
 */
export function createSemanticExampleValues(seed?: number | string): ExampleValueProvider {
  const context = createSemanticExampleContext(seed);
  const { faker } = context;

  return {
    string(field, constraints) {
      const hint = semanticHint(field);
      const person = context.personFor(field);

      if (constraints?.format === 'email') return context.nextExampleEmail(person);

      if (matches(hint, ['gebruikersnaam', 'username', 'user name', 'loginnaam'])) {
        return faker.internet.username(person);
      }
      if (matches(hint, ['voornaam', 'first name', 'firstname', 'given name'])) {
        return person.firstName;
      }
      if (matches(hint, ['achternaam', 'last name', 'lastname', 'surname', 'family name'])) {
        return person.lastName;
      }
      if (matches(hint, ['initialen', 'initials'])) {
        return `${person.firstName.charAt(0)}.${person.lastName.charAt(0)}.`;
      }
      if (matches(hint, ['tussenvoegsel', 'middle name', 'middlename'])) return 'van';
      if (matches(hint, ['email', 'e mail', 'emailadres', 'mailadres'])) {
        return context.nextExampleEmail(person);
      }
      if (matches(hint, ['telefoon', 'telefoonnummer', 'phone', 'mobile', 'mobiel'])) {
        return faker.phone.number({ style: 'international' });
      }
      if (matches(hint, ['postcode', 'postal code', 'zip code', 'zipcode'])) {
        return context.locationFor(field).postalCode;
      }
      if (matches(hint, ['huisnummer', 'house number', 'building number'])) {
        return faker.location.buildingNumber();
      }
      if (matches(hint, ['straatnaam', 'straat', 'street name', 'street'])) {
        return context.locationFor(field).street;
      }
      if (matches(hint, ['woonplaats', 'plaatsnaam', 'stad', 'city', 'town'])) {
        return context.locationFor(field).city;
      }
      if (matches(hint, ['land', 'country'])) return 'Nederland';
      if (matches(hint, ['beklaagde', 'respondent', 'accused', 'objector'])) {
        return fullName(context.personFor(field, 'subject'));
      }
      if (
        matches(hint, [
          'contactfunctionaris',
          'behandelaar',
          'case worker',
          'caseworker',
          'ambtenaar',
          'official',
        ])
      ) {
        return fullName(context.personFor(field, 'official'));
      }
      if (
        matches(hint, [
          'klager',
          'aanvrager',
          'applicant',
          'beneficiary',
          'begunstigde',
          'recipient',
          'ontvanger',
          'customer',
          'klant',
        ])
      ) {
        return fullName(person);
      }
      if (
        matches(hint, [
          'organisatie',
          'organization',
          'organisation',
          'bedrijfsnaam',
          'company',
          'gemeente',
          'municipality',
          'vendor',
          'leverancier',
          'sender',
          'afzender',
        ])
      ) {
        return faker.company.name();
      }
      if (matches(hint, ['team', 'afdeling', 'department'])) {
        return faker.helpers.arrayElement([
          'Team Dienstverlening',
          'Team Vergunningen',
          'Team Klachtafhandeling',
        ]);
      }
      if (matches(hint, ['communicatie', 'communication', 'contactwijze', 'contact method'])) {
        return faker.helpers.arrayElement(['e-mail', 'telefoon', 'brief']);
      }
      if (matches(hint, ['iban', 'rekeningnummer', 'bank account'])) {
        return faker.finance.iban({ countryCode: 'NL' });
      }
      if (matches(hint, ['bsn', 'burgerservicenummer', 'social security number'])) {
        return '111222333';
      }
      if (matches(hint, ['kenteken', 'licence plate', 'license plate', 'licenceplate'])) {
        return faker.helpers.fromRegExp(/[A-Z]{2}-[0-9]{2}-[A-Z]{2}/);
      }
      if (matches(hint, ['zaaknummer', 'case number', 'casenumber', 'dossiernummer'])) {
        return `ZAAK-${faker.number.int({ min: 100_000, max: 999_999 })}`;
      }
      if (matches(hint, ['factuurnummer', 'invoice number', 'invoicenumber'])) {
        return `FACT-2026-${faker.number.int({ min: 1_000, max: 9_999 })}`;
      }
      if (matches(hint, ['polisnummer', 'policy number', 'policynumber'])) {
        return `POL-${faker.string.alphanumeric({ length: 8, casing: 'upper' })}`;
      }
      if (matches(hint, ['ticketnummer', 'ticket number', 'ticketnumber'])) {
        return `TKT-${faker.number.int({ min: 10_000, max: 99_999 })}`;
      }
      if (matches(hint, ['kenmerk intern', 'kenmerk_intern', 'internal reference'])) {
        return `Z/${faker.number.int({ min: 10, max: 99 })}/${faker.number.int({ min: 100_000, max: 999_999 })}`;
      }
      if (matches(hint, ['kenmerk extern', 'kenmerk_extern', 'external reference'])) {
        return `EXT-${faker.number.int({ min: 1_000, max: 9_999 })}`;
      }
      if (matches(hint, ['referentienummer', 'reference number', 'reference'])) {
        return `REF-${faker.string.alphanumeric({ length: 8, casing: 'upper' })}`;
      }
      if (matches(hint, ['website', 'web site', 'url', 'homepage', 'payment link'])) {
        return faker.internet.url();
      }
      if (matches(hint, ['geboortedatum', 'birth date', 'birthdate', 'date of birth'])) {
        return faker.date
          .between({ from: '1970-01-01T00:00:00Z', to: '2000-12-31T00:00:00Z' })
          .toISOString()
          .slice(0, 10);
      }
      if (matches(hint, ['volledige naam', 'full name', 'fullname', 'contactnaam'])) {
        return fullName(person);
      }
      if (matches(hint, ['programma', 'program name', 'programname', 'product name'])) {
        return faker.commerce.productName();
      }
      if (matches(hint, ['onderwerp', 'subject', 'titel', 'title'])) {
        return faker.helpers.arrayElement([
          'Bevestiging van uw aanvraag',
          'Besluit over uw verzoek',
          'Aanvullende informatie',
        ]);
      }
      if (
        matches(hint, [
          'omschrijving',
          'description',
          'beschrijving',
          'toelichting',
          'comment',
          'notes',
          'body',
          'message',
          'bericht',
          'voorwaarden',
          'conditions',
          'feiten',
          'decision',
          'besluit',
          'oordeel',
        ])
      ) {
        return faker.helpers.arrayElement([
          'Uw aanvraag is door ons ontvangen en in behandeling genomen.',
          'Hierbij ontvangt u aanvullende informatie over de behandeling.',
          'Wij hebben de aangeleverde gegevens beoordeeld.',
        ]);
      }
      if (
        matches(hint, [
          'kenmerk',
          'registratienummer',
          'registration number',
          'nummer',
          'number',
          'identifier',
          'identification',
          'identication',
        ])
      ) {
        return `REF-${faker.string.alphanumeric({ length: 8, casing: 'upper' })}`;
      }
      if (matches(hint, ['naam', 'name'])) return fullName(person);

      return context.nextFallback(field);
    },

    number(field) {
      const hint = semanticHint(field);
      if (matches(hint, ['leeftijd', 'age'])) return 42;
      if (matches(hint, ['huisnummer', 'house number', 'building number'])) return 42;
      if (matches(hint, ['jaar', 'year'])) return 2026;
      if (matches(hint, ['bedrag', 'amount', 'prijs', 'price'])) return 125.5;
      if (matches(hint, ['aantal', 'quantity', 'count'])) return 3;
      if (matches(hint, ['percentage', 'rate', 'overspeed'])) return 10;
      if (matches(hint, ['snelheid', 'speed', 'measurement', 'measured'])) return 50;
      if (matches(hint, ['totaal', 'total', 'subtotal', 'fee', 'kosten'])) return 125.5;
      if (matches(hint, ['limiet', 'limit'])) return 100;
      return undefined;
    },

    boolean(field) {
      const hint = semanticHint(field);
      if (
        matches(hint, [
          'actief',
          'active',
          'enabled',
          'goedgekeurd',
          'approved',
          'corrected',
          'gecorrigeerd',
          'formeel',
          'formal',
          'via email',
          'measured',
        ])
      ) {
        return true;
      }
      return undefined;
    },

    arrayLength(_field, constraints) {
      if (constraints.maximum === 0) return 0;

      const minimum = Math.max(constraints.minimum, 2);
      const maximum = Math.max(constraints.minimum, Math.min(constraints.maximum ?? 3, 3));
      if (maximum < minimum) return maximum;
      return faker.number.int({ min: minimum, max: maximum });
    },
  };
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
