/**
 * k6-load-test.js - Main load test script for the Hazelcast Microservices Framework
 *
 * Supports multiple scenarios selectable via the K6_SCENARIO environment variable:
 *   - smoke       : 1 VU for 30s (quick sanity check)
 *   - ramp_up     : ramping-vus 0->10->50->100 over 5.5 minutes
 *   - constant_rate : constant-arrival-rate at configurable TPS (default 50)
 *   - (default)   : all scenarios run together
 *
 * Required environment variables:
 *   CUSTOMER_ID  - UUID of an existing customer (for orders/stock)
 *   PRODUCT_ID   - UUID of an existing product (for orders/stock)
 *
 * Optional environment variables:
 *   K6_SCENARIO  - which scenario to run (smoke|ramp_up|constant_rate)
 *   TPS          - target transactions per second for constant_rate (default 50)
 *   USE_GATEWAY  - 'true' to route through gateway instead of direct URLs
 *
 * Usage:
 *   k6 run -e CUSTOMER_ID=xxx -e PRODUCT_ID=yyy scripts/perf/k6-load-test.js
 *   k6 run -e K6_SCENARIO=smoke -e CUSTOMER_ID=xxx -e PRODUCT_ID=yyy scripts/perf/k6-load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';
import {
    SERVICES,
    HEADERS,
    THRESHOLDS,
    serviceUrl,
    checkStatus,
    uuid,
    randomInt,
} from './k6-config.js';

// ---------------------------------------------------------------------------
// Custom metrics
// ---------------------------------------------------------------------------
const orderCreateDuration   = new Trend('order_create_duration', true);
const stockReserveDuration  = new Trend('stock_reserve_duration', true);
const customerCreateDuration = new Trend('customer_create_duration', true);
const sagaE2eDuration       = new Trend('saga_e2e_duration', true);
const mixedWorkloadErrors   = new Rate('mixed_workload_errors');

// ---------------------------------------------------------------------------
// Test data - load from generated perf data JSON files (SharedArray)
// Falls back to single ID from environment variable if files are missing.
// ---------------------------------------------------------------------------
const PLACEHOLDER_UUID = '00000000-0000-4000-8000-000000000000';
const FALLBACK_CUSTOMER_ID = __ENV.CUSTOMER_ID || PLACEHOLDER_UUID;
const FALLBACK_PRODUCT_ID  = __ENV.PRODUCT_ID  || PLACEHOLDER_UUID;
const TPS = parseInt(__ENV.TPS || '50', 10);

let customerIds;
try {
    customerIds = new SharedArray('customers', function () {
        return JSON.parse(open('./data/customer-ids.json'));
    });
} catch (e) {
    customerIds = null;
}

let productIds;
try {
    productIds = new SharedArray('products', function () {
        return JSON.parse(open('./data/product-ids.json'));
    });
} catch (e) {
    productIds = null;
}

function pickCustomerId() {
    if (customerIds && customerIds.length > 0) {
        return customerIds[randomInt(0, customerIds.length - 1)];
    }
    return FALLBACK_CUSTOMER_ID;
}

function pickProductId() {
    if (productIds && productIds.length > 0) {
        return productIds[randomInt(0, productIds.length - 1)];
    }
    return FALLBACK_PRODUCT_ID;
}

// ---------------------------------------------------------------------------
// Scenario selection
// ---------------------------------------------------------------------------
const selectedScenario = __ENV.K6_SCENARIO || 'all';

function buildScenarios() {
    const allScenarios = {
        smoke: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: 30,
            maxDuration: '1m',
            exec: 'mixedWorkload',
            tags: { scenario: 'smoke' },
        },
        ramp_up: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s',  target: 10 },
                { duration: '1m',   target: 50 },
                { duration: '2m',   target: 100 },
                { duration: '1m',   target: 100 },  // hold at 100
                { duration: '1m',   target: 0 },     // ramp down
            ],
            exec: 'mixedWorkload',
            tags: { scenario: 'ramp_up' },
        },
        constant_rate: {
            executor: 'constant-arrival-rate',
            rate: TPS,
            timeUnit: '1s',
            duration: '3m',
            preAllocatedVUs: 50,
            maxVUs: 200,
            exec: 'mixedWorkload',
            tags: { scenario: 'constant_rate' },
        },
    };

    if (selectedScenario === 'all') {
        // Stagger start times so scenarios don't overlap too aggressively
        allScenarios.smoke.startTime = '0s';
        allScenarios.ramp_up.startTime = '40s';
        allScenarios.constant_rate.startTime = '6m30s';
        return allScenarios;
    }

    if (allScenarios[selectedScenario]) {
        const s = {};
        s[selectedScenario] = allScenarios[selectedScenario];
        return s;
    }

    // Unknown scenario name - fall back to smoke
    console.warn(`Unknown scenario "${selectedScenario}", falling back to smoke`);
    return { smoke: allScenarios.smoke };
}

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
    scenarios: buildScenarios(),
    thresholds: Object.assign({}, THRESHOLDS, {
        'order_create_duration':    ['p(95)<600', 'p(99)<1200'],
        'stock_reserve_duration':   ['p(95)<400', 'p(99)<800'],
        'customer_create_duration': ['p(95)<500', 'p(99)<1000'],
        'saga_e2e_duration':        ['p(95)<8000', 'p(99)<12000'],
        'mixed_workload_errors':    ['rate<0.05'],
    }),
};

// ---------------------------------------------------------------------------
// setup() - Health check all services before the test starts
// ---------------------------------------------------------------------------
export function setup() {
    console.log('--- Pre-flight health checks ---');

    const services = [
        { name: 'Account',   url: SERVICES.account },
        { name: 'Inventory', url: SERVICES.inventory },
        { name: 'Order',     url: SERVICES.order },
        { name: 'Payment',   url: SERVICES.payment },
    ];

    let allHealthy = true;
    for (let i = 0; i < services.length; i++) {
        const svc = services[i];
        const res = http.get(`${svc.url}/actuator/health`, { timeout: '5s' });
        const healthy = res.status === 200;
        console.log(`  ${svc.name} (${svc.url}): ${healthy ? 'OK' : 'FAILED (status=' + res.status + ')'}`);
        if (!healthy) {
            allHealthy = false;
        }
    }

    if (!allHealthy) {
        console.warn('WARNING: Not all services are healthy. Test results may be unreliable.');
    }

    if (customerIds && customerIds.length > 0) {
        console.log(`  Customer pool: ${customerIds.length} IDs (from data/customer-ids.json)`);
    } else {
        console.log(`  Customer ID: ${FALLBACK_CUSTOMER_ID} (single, no pool file)`);
    }
    if (productIds && productIds.length > 0) {
        console.log(`  Product pool: ${productIds.length} IDs (from data/product-ids.json)`);
    } else {
        console.log(`  Product ID: ${FALLBACK_PRODUCT_ID} (single, no pool file)`);
    }
    console.log(`  Target TPS:  ${TPS}`);
    console.log('--- Starting test ---');

    return {};
}

// ---------------------------------------------------------------------------
// Workload functions
// ---------------------------------------------------------------------------

function createOrder(customerId, productId) {
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

    const res = http.post(
        `${serviceUrl('order')}/api/orders`,
        payload,
        { headers: HEADERS, tags: { operation: 'create_order' } }
    );

    orderCreateDuration.add(res.timings.duration);

    const success = res.status === 200 || res.status === 201;
    if (!success) {
        mixedWorkloadErrors.add(1);
    } else {
        mixedWorkloadErrors.add(0);
    }

    return res;
}

function reserveStock(productId) {
    const payload = JSON.stringify({
        quantity: 1,
        orderId: `perftest-${uuid()}`,
    });

    const res = http.post(
        `${serviceUrl('inventory')}/api/products/${productId}/stock/reserve`,
        payload,
        { headers: HEADERS, tags: { operation: 'reserve_stock' } }
    );

    stockReserveDuration.add(res.timings.duration);

    const success = res.status === 200 || res.status === 201;
    if (!success) {
        mixedWorkloadErrors.add(1);
    } else {
        mixedWorkloadErrors.add(0);
    }

    return res;
}

function createCustomer() {
    const id = uuid().substring(0, 8);
    const payload = JSON.stringify({
        email: `perftest-${id}@example.com`,
        name: `Perf Test User ${id}`,
        address: `${randomInt(1, 9999)} Performance Ave`,
    });

    const res = http.post(
        `${serviceUrl('account')}/api/customers`,
        payload,
        { headers: HEADERS, tags: { operation: 'create_customer' } }
    );

    customerCreateDuration.add(res.timings.duration);

    const success = res.status === 200 || res.status === 201;
    if (!success) {
        mixedWorkloadErrors.add(1);
    } else {
        mixedWorkloadErrors.add(0);
    }

    return res;
}

/**
 * Creates an order and polls for saga completion.
 * Measures the full end-to-end saga latency.
 */
function createOrderAndWaitForSaga(customerId, productId) {
    const startTime = Date.now();

    // Create the order
    const createRes = createOrder(customerId, productId);
    if (createRes.status !== 200 && createRes.status !== 201) {
        return; // Cannot track saga if order creation failed
    }

    // Extract order ID from response
    let orderId = null;
    try {
        const body = JSON.parse(createRes.body);
        orderId = body.orderId || body.id;
    } catch (e) {
        console.warn('Could not parse order creation response');
        return;
    }

    if (!orderId) {
        return;
    }

    // Poll for saga completion (max 10 seconds, 200ms intervals)
    const maxWaitMs = 10000;
    const pollIntervalMs = 200;
    let elapsed = Date.now() - startTime;

    while (elapsed < maxWaitMs) {
        sleep(pollIntervalMs / 1000);

        const statusRes = http.get(
            `${serviceUrl('order')}/api/orders/${orderId}`,
            { headers: HEADERS, tags: { operation: 'saga_poll' } }
        );

        if (statusRes.status === 200) {
            try {
                const order = JSON.parse(statusRes.body);
                const status = (order.status || '').toUpperCase();
                if (status === 'CONFIRMED' || status === 'CANCELLED' ||
                    status === 'COMPLETED' || status === 'FAILED') {
                    const totalDuration = Date.now() - startTime;
                    sagaE2eDuration.add(totalDuration);
                    return;
                }
            } catch (e) {
                // Ignore parse errors; keep polling
            }
        }

        elapsed = Date.now() - startTime;
    }

    // Timed out waiting for saga completion
    const totalDuration = Date.now() - startTime;
    sagaE2eDuration.add(totalDuration);
    console.warn(`Saga timed out after ${totalDuration}ms for order ${orderId}`);
}

// ---------------------------------------------------------------------------
// Exported scenario executor: mixed workload (60/25/15 split)
// ---------------------------------------------------------------------------
export function mixedWorkload() {
    const customerId = pickCustomerId();
    const productId  = pickProductId();

    const roll = Math.random() * 100;

    if (roll < 60) {
        // 60% - Create orders (10% of these also track saga completion)
        if (roll < 6) {
            createOrderAndWaitForSaga(customerId, productId);
        } else {
            createOrder(customerId, productId);
        }
    } else if (roll < 85) {
        // 25% - Stock reservations
        reserveStock(productId);
    } else {
        // 15% - Customer creations
        createCustomer();
    }

    // Small think time to simulate realistic user behavior
    sleep(randomInt(100, 500) / 1000);
}

// ---------------------------------------------------------------------------
// handleSummary() - Export results to JSON file
// ---------------------------------------------------------------------------
export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const scenario = selectedScenario === 'all' ? 'all' : selectedScenario;
    const jsonPath = `scripts/perf/results/k6-results-${scenario}-${timestamp}.json`;

    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        [jsonPath]: JSON.stringify(data, null, 2),
    };
}
