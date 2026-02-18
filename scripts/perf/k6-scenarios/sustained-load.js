/**
 * sustained-load.js - k6 scenario for sustained load testing (30-60 minutes)
 *
 * Identical workload distribution to mixed-workload.js but designed for long runs:
 *   60% - Create orders          (Order Service)
 *   25% - Stock reservations     (Inventory Service)
 *   15% - Customer creations     (Account Service)
 *
 * Differences from mixed-workload.js:
 *   - Default duration: 30m (vs 3m)
 *   - Relaxed thresholds: p95 < 800ms, error < 2% (sustained load degrades)
 *   - Checkpoint logging: console log every 5 minutes with running totals
 *   - Designed for stability analysis, not peak performance measurement
 *
 * Required environment variables:
 *   CUSTOMER_ID  - UUID of an existing customer (for orders/stock)
 *   PRODUCT_ID   - UUID of an existing product (for orders/stock)
 *
 * Optional environment variables:
 *   DURATION          - test duration (default '30m')
 *   TPS               - target transactions per second (default 50)
 *   MAX_VUS           - maximum virtual users (default 200)
 *   CHECKPOINT_INTERVAL - checkpoint log interval in seconds (default 300)
 *
 * Usage:
 *   k6 run -e CUSTOMER_ID=xxx -e PRODUCT_ID=yyy scripts/perf/k6-scenarios/sustained-load.js
 *   k6 run -e CUSTOMER_ID=xxx -e PRODUCT_ID=yyy -e DURATION=1h -e TPS=30 scripts/perf/k6-scenarios/sustained-load.js
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
    checkStatus,
    uuid,
    randomInt,
} from '../k6-config.js';

// ---------------------------------------------------------------------------
// Custom metrics
// ---------------------------------------------------------------------------
const orderCreateDuration    = new Trend('order_create_duration', true);
const stockReserveDuration   = new Trend('stock_reserve_duration', true);
const customerCreateDuration = new Trend('customer_create_duration', true);
const sustainedErrors        = new Rate('sustained_errors');
const orderCount             = new Counter('order_count');
const stockReserveCount      = new Counter('stock_reserve_count');
const customerCreateCount    = new Counter('customer_create_count');
const totalIterations        = new Counter('total_iterations');

// ---------------------------------------------------------------------------
// Test data from environment
// ---------------------------------------------------------------------------
const PLACEHOLDER_UUID = '00000000-0000-4000-8000-000000000000';
const CUSTOMER_ID = __ENV.CUSTOMER_ID || PLACEHOLDER_UUID;
const PRODUCT_ID  = __ENV.PRODUCT_ID  || PLACEHOLDER_UUID;
const TPS         = parseInt(__ENV.TPS || '50', 10);
const DURATION    = __ENV.DURATION || '30m';
const MAX_VUS     = parseInt(__ENV.MAX_VUS || '200', 10);
const CHECKPOINT_INTERVAL = parseInt(__ENV.CHECKPOINT_INTERVAL || '300', 10);

// ---------------------------------------------------------------------------
// Checkpoint tracking (module-level state)
// ---------------------------------------------------------------------------
let lastCheckpointTime = 0;
let checkpointNumber   = 0;
let itersSinceCheckpoint  = 0;
let errorsSinceCheckpoint = 0;
let ordersSinceCheckpoint = 0;
let stockSinceCheckpoint  = 0;
let customersSinceCheckpoint = 0;

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
    scenarios: {
        sustained_load: {
            executor: 'constant-arrival-rate',
            rate: TPS,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.min(50, TPS),
            maxVUs: MAX_VUS,
            exec: 'sustainedWorkload',
            tags: { scenario: 'sustained_load' },
        },
    },
    thresholds: Object.assign({}, THRESHOLDS, {
        // Relaxed thresholds for sustained load (services may degrade over time)
        'order_create_duration':    ['p(95)<800', 'p(99)<1500'],
        'stock_reserve_duration':   ['p(95)<600', 'p(99)<1200'],
        'customer_create_duration': ['p(95)<700', 'p(99)<1400'],
        'sustained_errors':         ['rate<0.02'],
        'http_req_duration':        ['p(95)<800', 'p(99)<1500'],
        'http_req_failed':          ['rate<0.02'],
    }),
};

// ---------------------------------------------------------------------------
// setup() - Health check and banner
// ---------------------------------------------------------------------------
export function setup() {
    console.log('=== Sustained Load Test: Pre-flight checks ===');

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
        console.log(`  ${svc.name} (${svc.url}): ${healthy ? 'OK' : 'FAILED'}`);
        if (!healthy) {
            allHealthy = false;
        }
    }

    if (!allHealthy) {
        console.warn('WARNING: Not all services are healthy. Test results may be unreliable.');
    }

    console.log(`  Customer ID: ${CUSTOMER_ID}`);
    console.log(`  Product ID:  ${PRODUCT_ID}`);
    console.log(`  Target TPS:  ${TPS}`);
    console.log(`  Duration:    ${DURATION}`);
    console.log(`  Checkpoint:  every ${CHECKPOINT_INTERVAL}s`);
    console.log(`  Workload:    60% orders / 25% stock / 15% customers`);
    console.log('=== Starting sustained load test ===');

    return { customerId: CUSTOMER_ID, productId: PRODUCT_ID };
}

// ---------------------------------------------------------------------------
// Workload functions (same as mixed-workload.js)
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
        shippingAddress: `${randomInt(1, 9999)} Sustained Test St`,
    });

    const res = http.post(
        `${serviceUrl('order')}/api/orders`,
        payload,
        { headers: HEADERS, tags: { operation: 'create_order' } }
    );

    orderCreateDuration.add(res.timings.duration);
    const success = res.status === 200 || res.status === 201;
    if (success) {
        orderCount.add(1);
        sustainedErrors.add(0);
        ordersSinceCheckpoint++;
    } else {
        sustainedErrors.add(1);
        errorsSinceCheckpoint++;
    }
}

function reserveStock(productId) {
    const payload = JSON.stringify({
        quantity: 1,
        orderId: `sustained-${uuid()}`,
    });

    const res = http.post(
        `${serviceUrl('inventory')}/api/products/${productId}/stock/reserve`,
        payload,
        { headers: HEADERS, tags: { operation: 'reserve_stock' } }
    );

    stockReserveDuration.add(res.timings.duration);
    const success = res.status === 200 || res.status === 201;
    if (success) {
        stockReserveCount.add(1);
        sustainedErrors.add(0);
        stockSinceCheckpoint++;
    } else {
        sustainedErrors.add(1);
        errorsSinceCheckpoint++;
    }
}

function createCustomer() {
    const id = uuid().substring(0, 8);
    const payload = JSON.stringify({
        email: `sustained-${id}@example.com`,
        name: `Sustained Test User ${id}`,
        address: `${randomInt(1, 9999)} Stability Ave`,
    });

    const res = http.post(
        `${serviceUrl('account')}/api/customers`,
        payload,
        { headers: HEADERS, tags: { operation: 'create_customer' } }
    );

    customerCreateDuration.add(res.timings.duration);
    const success = res.status === 200 || res.status === 201;
    if (success) {
        customerCreateCount.add(1);
        sustainedErrors.add(0);
        customersSinceCheckpoint++;
    } else {
        sustainedErrors.add(1);
        errorsSinceCheckpoint++;
    }
}

// ---------------------------------------------------------------------------
// Checkpoint logging
// ---------------------------------------------------------------------------

function maybeLogCheckpoint() {
    const now = Math.floor(Date.now() / 1000);

    if (lastCheckpointTime === 0) {
        lastCheckpointTime = now;
        return;
    }

    const elapsed = now - lastCheckpointTime;
    if (elapsed >= CHECKPOINT_INTERVAL) {
        checkpointNumber++;
        const minutes = checkpointNumber * Math.round(CHECKPOINT_INTERVAL / 60);

        console.log(`--- CHECKPOINT ${checkpointNumber} (${minutes}m) ---`);
        console.log(`  Iterations since last: ${itersSinceCheckpoint}`);
        console.log(`  Errors since last:     ${errorsSinceCheckpoint}`);
        console.log(`  Orders:    ${ordersSinceCheckpoint}`);
        console.log(`  Stock:     ${stockSinceCheckpoint}`);
        console.log(`  Customers: ${customersSinceCheckpoint}`);
        console.log(`---`);

        // Reset per-checkpoint counters
        itersSinceCheckpoint = 0;
        errorsSinceCheckpoint = 0;
        ordersSinceCheckpoint = 0;
        stockSinceCheckpoint = 0;
        customersSinceCheckpoint = 0;
        lastCheckpointTime = now;
    }
}

// ---------------------------------------------------------------------------
// Main executor function
// ---------------------------------------------------------------------------
export function sustainedWorkload(data) {
    const customerId = data ? data.customerId : CUSTOMER_ID;
    const productId  = data ? data.productId : PRODUCT_ID;

    totalIterations.add(1);
    itersSinceCheckpoint++;

    const roll = Math.random() * 100;

    if (roll < 60) {
        createOrder(customerId, productId);
    } else if (roll < 85) {
        reserveStock(productId);
    } else {
        createCustomer();
    }

    // Checkpoint logging
    maybeLogCheckpoint();

    // Small think time
    sleep(randomInt(100, 500) / 1000);
}

// ---------------------------------------------------------------------------
// handleSummary() - Export results
// ---------------------------------------------------------------------------
export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const jsonPath = `scripts/perf/results/k6-results-sustained-load-${timestamp}.json`;

    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        [jsonPath]: JSON.stringify(data, null, 2),
    };
}
