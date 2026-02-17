/**
 * create-orders.js - Focused k6 scenario: order creation only
 *
 * Uses constant-arrival-rate to maintain a steady stream of order creation
 * requests and measures order creation latency.
 *
 * Required environment variables:
 *   CUSTOMER_ID  - UUID of an existing customer
 *   PRODUCT_ID   - UUID of an existing product
 *
 * Optional environment variables:
 *   TPS          - target transactions per second (default 50)
 *   DURATION     - test duration (default '3m')
 *   MAX_VUS      - maximum virtual users (default 200)
 *
 * Usage:
 *   k6 run -e CUSTOMER_ID=xxx -e PRODUCT_ID=yyy scripts/perf/k6-scenarios/create-orders.js
 *   k6 run -e CUSTOMER_ID=xxx -e PRODUCT_ID=yyy -e TPS=100 scripts/perf/k6-scenarios/create-orders.js
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
const orderCreateDuration = new Trend('order_create_duration', true);
const orderCreateErrors   = new Rate('order_create_errors');
const ordersCreated       = new Counter('orders_created');

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
        create_orders: {
            executor: 'constant-arrival-rate',
            rate: TPS,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.min(50, TPS),
            maxVUs: MAX_VUS,
            exec: 'createOrder',
            tags: { scenario: 'create_orders' },
        },
    },
    thresholds: Object.assign({}, THRESHOLDS, {
        'order_create_duration': ['p(95)<600', 'p(99)<1200'],
        'order_create_errors':   ['rate<0.05'],
    }),
};

// ---------------------------------------------------------------------------
// setup() - Health check order service
// ---------------------------------------------------------------------------
export function setup() {
    console.log('--- Create Orders: Pre-flight checks ---');

    const res = http.get(`${SERVICES.order}/actuator/health`, { timeout: '5s' });
    const healthy = res.status === 200;
    console.log(`  Order Service (${SERVICES.order}): ${healthy ? 'OK' : 'FAILED'}`);

    if (!healthy) {
        console.warn('WARNING: Order Service is not healthy. Test results may be unreliable.');
    }

    console.log(`  Customer ID: ${CUSTOMER_ID}`);
    console.log(`  Product ID:  ${PRODUCT_ID}`);
    console.log(`  Target TPS:  ${TPS}`);
    console.log(`  Duration:    ${DURATION}`);
    console.log('--- Starting test ---');

    return { customerId: CUSTOMER_ID, productId: PRODUCT_ID };
}

// ---------------------------------------------------------------------------
// Main executor function
// ---------------------------------------------------------------------------
export function createOrder(data) {
    const customerId = data ? data.customerId : CUSTOMER_ID;
    const productId  = data ? data.productId : PRODUCT_ID;

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

    const success = check(res, {
        'order created successfully': (r) => r.status === 200 || r.status === 201,
    });

    if (success) {
        ordersCreated.add(1);
        orderCreateErrors.add(0);
    } else {
        orderCreateErrors.add(1);
    }

    // Minimal think time
    sleep(randomInt(50, 200) / 1000);
}

// ---------------------------------------------------------------------------
// handleSummary() - Export results
// ---------------------------------------------------------------------------
export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const jsonPath = `scripts/perf/results/k6-results-create-orders-${timestamp}.json`;

    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        [jsonPath]: JSON.stringify(data, null, 2),
    };
}
