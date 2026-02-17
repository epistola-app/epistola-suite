/**
 * PDF Preview functionality for template versions.
 * Fetches PDF from preview endpoint and opens in a new browser tab.
 */

async function previewPdf(button) {
    const tenantId = button.dataset.tenantId;
    const templateId = button.dataset.templateId;
    const variantId = button.dataset.variantId;
    const testDataJson = button.dataset.testData || '{}';

    const url = `/tenants/${tenantId}/templates/${templateId}/variants/${variantId}/preview`;

    // Show loading state
    const originalContent = button.innerHTML;
    button.disabled = true;

    try {
        // Parse the test data and wrap it in the expected request format
        const testData = JSON.parse(testDataJson);
        const requestBody = JSON.stringify({ data: testData });

        // Get CSRF token from cookie (set by Spring Security CookieCsrfTokenRepository)
        const csrfToken = typeof window.getCsrfToken === 'function' ? window.getCsrfToken() : '';

        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': csrfToken
            },
            body: requestBody
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || `HTTP ${response.status}`);
        }

        const blob = await response.blob();
        const blobUrl = URL.createObjectURL(blob);
        window.open(blobUrl, '_blank');
        // Revoke blob URL after the new tab has had time to load
        setTimeout(() => URL.revokeObjectURL(blobUrl), 60000);
    } catch (error) {
        console.error('PDF preview failed:', error);
        alert('Failed to generate PDF preview: ' + error.message);
    } finally {
        button.innerHTML = originalContent;
        button.disabled = false;
    }
}
