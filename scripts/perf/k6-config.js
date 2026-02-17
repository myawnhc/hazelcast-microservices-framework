/**
 * k6-config.js - Shared configuration module for k6 performance tests
 *
 * Exports service URLs, default thresholds, common headers, and helper functions
 * used by all k6 test scripts in this directory.
 *
 * Usage:
 *   import { SERVICES, GATEWAY, THRESHOLDS, HEADERS, checkStatus } from './k6-config.js';
 */

// ---------------------------------------------------------------------------
// Service URLs - direct access (bypassing gateway)
// ---------------------------------------------------------------------------
export const SERVICES = {
    account:   __ENV.ACCOUNT_URL   || 'http://localhost:8081',
    inventory: __ENV.INVENTORY_URL || 'http://localhost:8082',
    order:     __ENV.ORDER_URL     || 'http://localhost:8083',
    payment:   __ENV.PAYMENT_URL   || 'http://localhost:8084',
};

// ---------------------------------------------------------------------------
// API Gateway URL - proxies all services
// ---------------------------------------------------------------------------
export const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';

// ---------------------------------------------------------------------------
// Whether to route requests through the gateway instead of direct service URLs
// ---------------------------------------------------------------------------
export const USE_GATEWAY = (__ENV.USE_GATEWAY || 'false') === 'true';

/**
 * Returns the base URL for the given service name.
 * If USE_GATEWAY is true, returns the gateway URL; otherwise the direct URL.
 */
export function serviceUrl(serviceName) {
    if (USE_GATEWAY) {
        return GATEWAY;
    }
    return SERVICES[serviceName];
}

// ---------------------------------------------------------------------------
// Default thresholds applied to all test scripts
// ---------------------------------------------------------------------------
export const THRESHOLDS = {
    http_req_duration: [
        'p(95)<500',   // 95th percentile under 500ms
        'p(99)<1000',  // 99th percentile under 1000ms
    ],
    http_req_failed: [
        'rate<0.01',   // Error rate under 1%
    ],
};

// ---------------------------------------------------------------------------
// Common HTTP headers
// ---------------------------------------------------------------------------
export const HEADERS = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
};

// ---------------------------------------------------------------------------
// Helper: check HTTP response status and tag failures
// ---------------------------------------------------------------------------

/**
 * Checks that the response has the expected HTTP status code.
 * Returns true if the status matches, false otherwise.
 * Logs a warning on mismatch for easier debugging.
 *
 * @param {object} res - k6 HTTP response object
 * @param {number} expectedStatus - expected HTTP status code (e.g. 200, 201)
 * @returns {boolean}
 */
export function checkStatus(res, expectedStatus) {
    const passed = res.status === expectedStatus;
    if (!passed) {
        console.warn(
            `Unexpected status: ${res.status} (expected ${expectedStatus}) ` +
            `for ${res.request.method} ${res.request.url} - body: ${res.body}`
        );
    }
    return passed;
}

// ---------------------------------------------------------------------------
// Helper: generate a v4-style UUID (good enough for test data)
// ---------------------------------------------------------------------------
export function uuid() {
    // Simple pseudo-UUID using Math.random; sufficient for load testing
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

// ---------------------------------------------------------------------------
// Helper: random integer in [min, max]
// ---------------------------------------------------------------------------
export function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}
