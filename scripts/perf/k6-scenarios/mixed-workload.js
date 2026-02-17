/**
 * mixed-workload.js - Focused k6 scenario: mixed workload (60/25/15 split)
 *
 * Workload distribution:
 *   60% - Create orders          (Order Service)
 *   25% - Stock reservations     (Inventory Service)
 *   15% - Customer creations     (Account Service)
 *
 * Uses constant-arrival-rate executor for steady throughput.
 *
 * Required environment variables:
 *   CUSTOMER_ID  - UUID of an existing customer (for orders/stock)
 *   PRODUCT_ID   - UUID of an existing product (for orders/stock)
 *
 * Optional environment variables:
 *   TPS          - target transactions per second (default 50)
 *   DURATION     - test duration (default '3m')
 *   MAX_VUS      - maximum virtual users (default 200)
 *
 * Usage:
 *   k6 run -e CUSTOMER_ID=xxx -e PRODUCT_ID=yyy scripts/perf/k6-scenarios/mixed-workload.js
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
const mixedWorkloadErrors    = new Rate('mixed_workload_errors');
const orderCount             = new Counter('order_count');
const stockReserveCount      = new Counter('stock_reserve_count');
const customerCreateCount    = new Counter('customer_create_count');

// ---------------------------------------------------------------------------
// Test data from environment
// ---------------------------------------------------------------------------
const PLACEHOLDER_UUID = '00000000-0000-4000-8000-000000000000';
const CUSTOMER_ID = __ENV.CUSTOMER_ID || PLACEHOLDER_UUID;
const PRODUCT_ID  = __ENV.PRODUCT_ID  || PLACEHOLDER_UUID;
const TPS         = parseInt(__ENV.TPS || '50', 10);
const DURATION    = __ENV.DURATION || '3m';
const MAX_VUS     = parseInt(__ENV.MAX_VUS || '200', 10);

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
    scenarios: {
        mixed_workload: {
            executor: 'constant-arrival-rate',
            rate: TPS,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.min(50, TPS),
            maxVUs: MAX_VUS,
            exec: 'mixedWorkload',
            tags: { scenario: 'mixed_workload' },
        },
    },
    thresholds: Object.assign({}, THRESHOLDS, {
        'order_create_duration':    ['p(95)<600', 'p(99)<1200'],
        'stock_reserve_duration':   ['p(95)<400', 'p(99)<800'],
        'customer_create_duration': ['p(95)<500', 'p(99)<1000'],
        'mixed_workload_errors':    ['rate<0.05'],
    }),
};

// ---------------------------------------------------------------------------
// setup() - Health check all services
// ---------------------------------------------------------------------------
export function setup() {
    console.log('--- Mixed Workload: Pre-flight checks ---');

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
    console.log(`  Workload:    60% orders / 25% stock / 15% customers`);
    console.log('--- Starting test ---');

    return { customerId: CUSTOMER_ID, productId: PRODUCT_ID };
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
    if (success) {
        orderCount.add(1);
        mixedWorkloadErrors.add(0);
    } else {
        mixedWorkloadErrors.add(1);
    }
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
    if (success) {
        stockReserveCount.add(1);
        mixedWorkloadErrors.add(0);
    } else {
        mixedWorkloadErrors.add(1);
    }
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
    if (success) {
        customerCreateCount.add(1);
        mixedWorkloadErrors.add(0);
    } else {
        mixedWorkloadErrors.add(1);
    }
}

// ---------------------------------------------------------------------------
// Main executor function
// ---------------------------------------------------------------------------
export function mixedWorkload(data) {
    const customerId = data ? data.customerId : CUSTOMER_ID;
    const productId  = data ? data.productId : PRODUCT_ID;

    const roll = Math.random() * 100;

    if (roll < 60) {
        createOrder(customerId, productId);
    } else if (roll < 85) {
        reserveStock(productId);
    } else {
        createCustomer();
    }

    // Small think time to simulate realistic user behavior
    sleep(randomInt(100, 500) / 1000);
}

// ---------------------------------------------------------------------------
// handleSummary() - Export results
// ---------------------------------------------------------------------------
export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const jsonPath = `scripts/perf/results/k6-results-mixed-workload-${timestamp}.json`;

    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        [jsonPath]: JSON.stringify(data, null, 2),
    };
}
