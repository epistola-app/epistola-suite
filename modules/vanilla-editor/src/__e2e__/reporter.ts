/**
 * Custom Playwright Reporter
 *
 * Captures detailed state on test failures.
 */

export class E2EFailureReporter {
  onBegin(): void {
    console.log('Starting E2E tests...');
  }
}

export default E2EFailureReporter;
