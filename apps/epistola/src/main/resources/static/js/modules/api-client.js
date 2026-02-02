/**
 * Shared API client for making authenticated requests with CSRF protection.
 * Used by theme-editor.js and template-detail.js modules.
 */

/**
 * Gets the CSRF token from the meta tag if present.
 * @returns {string} The CSRF token or empty string if not found
 */
export function getCsrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    return meta?.content || '';
}

/**
 * Makes a JSON API request with proper headers and error handling.
 * @param {string} url - The URL to request
 * @param {Object} options - Fetch options
 * @param {string} [options.method='GET'] - HTTP method
 * @param {Object} [options.body] - Request body (will be JSON stringified)
 * @returns {Promise<{ok: boolean, data?: any, error?: string}>}
 */
export async function apiRequest(url, { method = 'GET', body } = {}) {
    try {
        const headers = {
            'Content-Type': 'application/json',
        };

        const csrfToken = getCsrfToken();
        if (csrfToken) {
            headers['X-CSRF-TOKEN'] = csrfToken;
        }

        const fetchOptions = {
            method,
            headers,
        };

        if (body !== undefined) {
            fetchOptions.body = JSON.stringify(body);
        }

        const response = await fetch(url, fetchOptions);

        if (!response.ok) {
            let errorMessage = `HTTP ${response.status}`;
            try {
                const errorData = await response.json();
                errorMessage = errorData.message || errorData.error || errorMessage;
            } catch {
                // Response wasn't JSON, use status text
                errorMessage = response.statusText || errorMessage;
            }
            return { ok: false, error: errorMessage };
        }

        // Handle empty responses (204 No Content)
        if (response.status === 204) {
            return { ok: true, data: null };
        }

        const data = await response.json();
        return { ok: true, data };
    } catch (error) {
        return { ok: false, error: error.message };
    }
}

/**
 * Makes a PATCH request to update a resource.
 * @param {string} url - The URL to request
 * @param {Object} body - Request body
 * @returns {Promise<{ok: boolean, data?: any, error?: string}>}
 */
export function patch(url, body) {
    return apiRequest(url, { method: 'PATCH', body });
}

/**
 * Makes a POST request.
 * @param {string} url - The URL to request
 * @param {Object} body - Request body
 * @returns {Promise<{ok: boolean, data?: any, error?: string}>}
 */
export function post(url, body) {
    return apiRequest(url, { method: 'POST', body });
}

/**
 * Makes a DELETE request.
 * @param {string} url - The URL to request
 * @returns {Promise<{ok: boolean, data?: any, error?: string}>}
 */
export function del(url) {
    return apiRequest(url, { method: 'DELETE' });
}

/**
 * Shows an alert for API errors.
 * @param {string} action - Description of what failed
 * @param {string} error - Error message
 */
export function showError(action, error) {
    alert(`Failed to ${action}: ${error}`);
}

/**
 * Shows a success message.
 * @param {string} message - Success message
 */
export function showSuccess(message) {
    alert(message);
}
