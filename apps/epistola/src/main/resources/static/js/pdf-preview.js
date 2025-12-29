/**
 * PDF Preview functionality for template versions.
 * Fetches PDF from preview endpoint and opens in a new browser tab.
 */

async function previewPdf(button) {
    const tenantId = button.dataset.tenantId;
    const templateId = button.dataset.templateId;
    const variantId = button.dataset.variantId;
    const testData = button.dataset.testData || '{}';

    const url = `/tenants/${tenantId}/templates/${templateId}/variants/${variantId}/preview`;

    // Show loading state
    const originalText = button.textContent;
    button.textContent = 'Loading...';
    button.disabled = true;

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: testData
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || `HTTP ${response.status}`);
        }

        const blob = await response.blob();
        const blobUrl = URL.createObjectURL(blob);
        window.open(blobUrl, '_blank');
    } catch (error) {
        console.error('PDF preview failed:', error);
        alert('Failed to generate PDF preview: ' + error.message);
    } finally {
        button.textContent = originalText;
        button.disabled = false;
    }
}
