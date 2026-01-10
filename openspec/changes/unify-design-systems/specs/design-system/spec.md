# Design System Specification

## ADDED Requirements

### Requirement: Design Tokens

The system SHALL provide CSS custom properties defining the visual design tokens (colors, spacing, radius) that are shared across all UI components.

#### Scenario: Color tokens available
- **WHEN** any page loads the design system CSS
- **THEN** CSS custom properties for colors are available (e.g., `--color-primary`, `--color-destructive`, `--color-muted`)

#### Scenario: Spacing tokens available
- **WHEN** any page loads the design system CSS
- **THEN** CSS custom properties for spacing are available (e.g., `--spacing-1` through `--spacing-8`)

#### Scenario: Radius tokens available
- **WHEN** any page loads the design system CSS
- **THEN** CSS custom properties for border radius are available (e.g., `--radius`, `--radius-sm`, `--radius-lg`)

---

### Requirement: Button Component

The system SHALL provide button component classes with consistent styling across React and Thymeleaf contexts.

#### Scenario: Base button class
- **WHEN** an element has class `btn`
- **THEN** it displays as an inline-flex centered element with default (primary) styling

#### Scenario: Button variants
- **WHEN** a button has variant class (`btn-primary`, `btn-secondary`, `btn-destructive`, `btn-outline`, `btn-ghost`)
- **THEN** it displays with the corresponding visual style

#### Scenario: Button sizes
- **WHEN** a button has size class (`btn-sm`, `btn-lg`)
- **THEN** it displays with the corresponding height and padding

#### Scenario: Icon button
- **WHEN** a button has class `btn-icon`
- **THEN** it displays as a square button suitable for icon-only content

#### Scenario: Disabled state
- **WHEN** a button has the `disabled` attribute
- **THEN** it displays with reduced opacity and non-interactive cursor

#### Scenario: Link as button
- **WHEN** an anchor (`<a>`) element has class `btn`
- **THEN** it displays visually identical to a `<button>` with the same classes

---

### Requirement: Input Component

The system SHALL provide input component classes for form fields with consistent styling.

#### Scenario: Text input styling
- **WHEN** an input element has class `input`
- **THEN** it displays with consistent border, padding, and focus states

#### Scenario: Textarea styling
- **WHEN** a textarea element has class `textarea`
- **THEN** it displays with consistent border, padding, and focus states matching input

#### Scenario: Input error state
- **WHEN** an input has class `input-error` or `aria-invalid="true"`
- **THEN** it displays with destructive/error border color

#### Scenario: Input disabled state
- **WHEN** an input has the `disabled` attribute
- **THEN** it displays with reduced opacity and non-editable appearance

---

### Requirement: Badge Component

The system SHALL provide badge component classes for status indicators and labels.

#### Scenario: Base badge class
- **WHEN** an element has class `badge`
- **THEN** it displays as an inline pill-shaped element with default styling

#### Scenario: Badge variants
- **WHEN** a badge has variant class (`badge-primary`, `badge-secondary`, `badge-destructive`, `badge-outline`)
- **THEN** it displays with the corresponding visual style

---

### Requirement: Card Component

The system SHALL provide card component classes for content containers.

#### Scenario: Base card class
- **WHEN** an element has class `card`
- **THEN** it displays with background, border/shadow, and border-radius

#### Scenario: Card sections
- **WHEN** a card contains elements with classes `card-header`, `card-content`, `card-footer`
- **THEN** each section displays with appropriate padding and separation

---

### Requirement: Visual Consistency

The system SHALL ensure visual consistency between React components and Thymeleaf templates.

#### Scenario: Button parity
- **WHEN** a React `<Button variant="destructive">` and a Thymeleaf `<button class="btn btn-destructive">` are rendered
- **THEN** they are visually identical (same colors, sizing, hover states)

#### Scenario: Cross-context token usage
- **WHEN** design tokens are updated (e.g., `--color-primary` changed)
- **THEN** both React components and Thymeleaf templates reflect the change
