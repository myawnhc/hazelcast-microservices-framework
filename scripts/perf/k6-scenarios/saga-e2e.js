/**
 * saga-e2e.js - Focused k6 scenario: end-to-end saga latency measurement
 *
 * Creates an order and then polls the Order Service until the saga completes
 * (order status becomes CONFIRMED, CANCELLED, COMPLETED, or FAILED).
 * Measures the full end-to-end saga latency including:
 *   1. Order creation (command accepted)
 *   2. Inventory stock reservation (via saga)
 *   3. Payment processing (via saga)
 *   4. Order confirmation/cancellation (saga result)
 *
 * Uses ramping-vus with low concurrency (5-20 VUs) since each iteration
 * involves polling and is inherently slower than fire-and-forget requests.
 *
 * Required environment variables:
 *   CUSTOMER_ID  - UUID of an existing customer
 *   PRODUCT_ID   - UUID of an existing product
 *
 * Optional environment variables:
 *   MAX_VUS      - peak virtual users (default 20)
 *   POLL_INTERVAL_MS - milliseconds between status polls (default 200)
 *   SAGA_TIMEOUT_MS  - max time to wait for saga completion (default 10000)
 *
 * Usage:
 *   k6 run -e CUSTOMER_ID=xxx -e PRODUCT_ID=yyy scripts/perf/k6-scenarios/saga-e2e.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';
import {
    SERVICES,
    HEADERS,
    THRESHOLDS,
    serviceUrl,
    uuid,
    randomInt,
} from '../k6-config.js';

// ---------------------------------------------------------------------------
// Custom metrics
// ---------------------------------------------------------------------------
const sagaE2eDuration     = new Trend('saga_e2e_duration', true);
const orderCreateDuration = new Trend('order_create_duration', true);
const sagaTimeouts        = new Rate('saga_timeouts');
const sagaConfirmed       = new Counter('saga_confirmed');
const sagaCancelled       = new Counter('saga_cancelled');
const sagaTimedOut        = new Counter('saga_timed_out');
const sagaPollCount       = new Counter('saga_poll_count');

// ---------------------------------------------------------------------------
// Test data from environment
// ---------------------------------------------------------------------------
const PLACEHOLDER_UUID    = '00000000-0000-4000-8000-000000000000';
const CUSTOMER_ID         = __ENV.CUSTOMER_ID || PLACEHOLDER_UUID;
const PRODUCT_ID          = __ENV.PRODUCT_ID  || PLACEHOLDER_UUID;
const MAX_VUS             = parseInt(__ENV.MAX_VUS || '20', 10);
const POLL_INTERVAL_MS    = parseInt(__ENV.POLL_INTERVAL_MS || '200', 10);
const SAGA_TIMEOUT_MS     = parseInt(__ENV.SAGA_TIMEOUT_MS || '10000', 10);

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
    scenarios: {
        saga_e2e: {
            executor: 'ramping-vus',
            startVUs: 1,
            stages: [
                { duration: '30s', target: 5 },
                { duration: '1m',  target: Math.floor(MAX_VUS / 2) },
                { duration: '2m',  target: MAX_VUS },
                { duration: '1m',  target: MAX_VUS },  // hold at peak
                { duration: '30s', target: 0 },          // ramp down
            ],
            exec: 'sagaEndToEnd',
            tags: { scenario: 'saga_e2e' },
        },
    },
    thresholds: Object.assign({}, THRESHOLDS, {
        'saga_e2e_duration':     ['p(95)<8000', 'p(99)<12000'],
        'order_create_duration': ['p(95)<600', 'p(99)<1200'],
        'saga_timeouts':         ['rate<0.10'],  // Less than 10% timeouts
    }),
};

// ---------------------------------------------------------------------------
// setup() - Health check services involved in the saga
// ---------------------------------------------------------------------------
export function setup() {
    console.log('--- Saga E2E: Pre-flight checks ---');

    const services = [
        { name: 'Order',     url: SERVICES.order },
        { name: 'Inventory', url: SERVICES.inventory },
        { name: 'Payment',   url: SERVICES.payment },
    ];

    let allHealthy = true;
    for (let i = 0; i < services.length; i++) {
        const svc = services[i];
        const res = http.get(`${svc.url}/actuator/health`, { timeout: '5s' });
        const healthy = res.status === 200;
        console.log(`  ${svc.name} (${svc.url}): ${healthy ? 'OK' : 'FAILED'}`);
        if (!healthy) {
            allHealthy = false;
        }
    }

    if (!allHealthy) {
        console.warn('WARNING: Not all saga services are healthy. Test results may be unreliable.');
    }

    console.log(`  Customer ID:      ${CUSTOMER_ID}`);
    console.log(`  Product ID:       ${PRODUCT_ID}`);
    console.log(`  Max VUs:          ${MAX_VUS}`);
    console.log(`  Poll interval:    ${POLL_INTERVAL_MS}ms`);
    console.log(`  Saga timeout:     ${SAGA_TIMEOUT_MS}ms`);
    console.log('--- Starting test ---');

    return { customerId: CUSTOMER_ID, productId: PRODUCT_ID };
}

// ---------------------------------------------------------------------------
// Main executor function: create order and poll for saga completion
// ---------------------------------------------------------------------------
export function sagaEndToEnd(data) {
    const customerId = data ? data.customerId : CUSTOMER_ID;
    const productId  = data ? data.productId : PRODUCT_ID;

    const sagaStartTime = Date.now();

    // Step 1: Create the order
    const payload = JSON.stringify({
        customerId: customerId,
        lineItems: [
            {
                productId: productId,
                quantity: 1,
                unitPrice: '10.00',
            },
        ],
        shippingAddress: `${randomInt(1, 9999)} Perf Test St`,
    });

    const createRes = http.post(
        `${serviceUrl('order')}/api/orders`,
        payload,
        { headers: HEADERS, tags: { operation: 'saga_create_order' } }
    );

    orderCreateDuration.add(createRes.timings.duration);

    const orderCreated = check(createRes, {
        'order created for saga': (r) => r.status === 200 || r.status === 201,
    });

    if (!orderCreated) {
        sagaTimeouts.add(1);
        sagaTimedOut.add(1);
        console.warn(`Order creation failed: status=${createRes.status}`);
        sleep(1); // Back off before next iteration
        return;
    }

    // Extract order ID from response
    let orderId = null;
    try {
        const body = JSON.parse(createRes.body);
        orderId = body.orderId || body.id;
    } catch (e) {
        console.warn('Could not parse order creation response body');
        sagaTimeouts.add(1);
        sagaTimedOut.add(1);
        sleep(1);
        return;
    }

    if (!orderId) {
        console.warn('No order ID found in response');
        sagaTimeouts.add(1);
        sagaTimedOut.add(1);
        sleep(1);
        return;
    }

    // Step 2: Poll for saga completion
    let sagaComplete = false;
    let finalStatus = 'UNKNOWN';
    let pollCount = 0;

    while (!sagaComplete) {
        const elapsed = Date.now() - sagaStartTime;
        if (elapsed >= SAGA_TIMEOUT_MS) {
            break; // Timeout
        }

        sleep(POLL_INTERVAL_MS / 1000);
        pollCount++;
        sagaPollCount.add(1);

        const statusRes = http.get(
            `${serviceUrl('order')}/api/orders/${orderId}`,
            { headers: HEADERS, tags: { operation: 'saga_poll_status' } }
        );

        if (statusRes.status === 200) {
            try {
                const order = JSON.parse(statusRes.body);
                const status = (order.status || '').toUpperCase();

                if (status === 'CONFIRMED' || status === 'COMPLETED') {
                    sagaComplete = true;
                    finalStatus = status;
                    sagaConfirmed.add(1);
                } else if (status === 'CANCELLED' || status === 'FAILED') {
                    sagaComplete = true;
                    finalStatus = status;
                    sagaCancelled.add(1);
                }
                // Otherwise keep polling (PENDING, PROCESSING, etc.)
            } catch (e) {
                // Ignore parse errors; keep polling
            }
        }
    }

    const totalDuration = Date.now() - sagaStartTime;
    sagaE2eDuration.add(totalDuration);

    if (!sagaComplete) {
        sagaTimeouts.add(1);
        sagaTimedOut.add(1);
        console.warn(`Saga timed out after ${totalDuration}ms for order ${orderId} (${pollCount} polls)`);
    } else {
        sagaTimeouts.add(0);
    }

    // Brief pause between saga iterations to avoid overwhelming the system
    sleep(randomInt(500, 1500) / 1000);
}

// ---------------------------------------------------------------------------
// handleSummary() - Export results
// ---------------------------------------------------------------------------
export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const jsonPath = `scripts/perf/results/k6-results-saga-e2e-${timestamp}.json`;

    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        [jsonPath]: JSON.stringify(data, null, 2),
    };
}
