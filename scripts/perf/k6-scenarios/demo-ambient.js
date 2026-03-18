/**
 * demo-ambient.js - k6 demo load generator
 *
 * Generates visually interesting, mixed traffic for demos:
 *   50% - Create orders using existing customers + products (shows sagas)
 *   20% - GET customer views (read traffic for dashboards)
 *   15% - GET product views (inventory monitoring)
 *   10% - Create high-quantity orders that fail (saga compensation)
 *    5% - Create new customers (small growth, bounded)
 *
 * Default: 30 TPS for 10 minutes — impressive for a quick customer demo.
 * For trade shows: k6 run -e TPS=3 -e DURATION=8h demo-ambient.js
 *
 * Key design choices:
 *   - Entity reuse — random picks from preloaded customer/product lists
 *   - Mixed saga outcomes — some succeed, some compensate (for dashboards)
 *   - Random think time — looks natural, not robotic
 *
 * Required files:
 *   scripts/perf/data/customer-ids.json  - JSON array of customer UUIDs
 *   scripts/perf/data/product-ids.json   - JSON array of product UUIDs
 *
 * Optional environment variables:
 *   TPS        - target transactions per second (default 30)
 *   DURATION   - test duration (default '10m')
 *   MAX_VUS    - maximum virtual users (default 100)
 *
 * Usage:
 *   k6 run scripts/perf/k6-scenarios/demo-ambient.js              # Quick demo: 30 TPS, 10m
 *   k6 run -e TPS=3 -e DURATION=8h scripts/perf/k6-scenarios/demo-ambient.js  # Trade show
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import {
    SERVICES,
    HEADERS,
    serviceUrl,
    checkStatus,
    uuid,
    randomInt,
} from '../k6-config.js';

// ---------------------------------------------------------------------------
// Custom metrics
// ---------------------------------------------------------------------------
const orderSuccessDuration  = new Trend('demo_order_success_duration', true);
const orderFailDuration     = new Trend('demo_order_fail_duration', true);
const customerViewDuration  = new Trend('demo_customer_view_duration', true);
const productViewDuration   = new Trend('demo_product_view_duration', true);
const customerCreateDuration = new Trend('demo_customer_create_duration', true);
const demoErrors            = new Rate('demo_errors');
const sagaSuccessCount      = new Counter('demo_saga_success');
const sagaFailCount         = new Counter('demo_saga_fail');
const readCount             = new Counter('demo_reads');
const newCustomerCount      = new Counter('demo_new_customers');

// ---------------------------------------------------------------------------
// Preloaded entity lists (populated at init time)
// ---------------------------------------------------------------------------
let customerIds = null;
try {
    customerIds = new SharedArray('customers', function () {
        return JSON.parse(open('../data/customer-ids.json'));
    });
} catch (e) {
    // Fallback: use env var or generate
}

let productIds = null;
try {
    productIds = new SharedArray('products', function () {
        return JSON.parse(open('../data/product-ids.json'));
    });
} catch (e) {
    // Fallback: use env var or generate
}

function pickCustomerId() {
    if (customerIds && customerIds.length > 0) {
        return customerIds[randomInt(0, customerIds.length - 1)];
    }
    return __ENV.CUSTOMER_ID || uuid();
}

function pickProductId() {
    if (productIds && productIds.length > 0) {
        return productIds[randomInt(0, productIds.length - 1)];
    }
    return __ENV.PRODUCT_ID || uuid();
}

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
const TPS      = parseInt(__ENV.TPS || '30');
const DURATION = __ENV.DURATION || '10m';
const MAX_VUS  = parseInt(__ENV.MAX_VUS || '100');

export const options = {
    scenarios: {
        ambient: {
            executor: 'constant-arrival-rate',
            rate: TPS,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.min(TPS * 2, MAX_VUS),
            maxVUs: MAX_VUS,
        },
    },
    thresholds: {
        'demo_errors': ['rate<0.3'],  // Relaxed: 30% error ok (deliberate failures)
        'demo_order_success_duration': ['p(95)<2000'],
    },
};

// ---------------------------------------------------------------------------
// Workload functions
// ---------------------------------------------------------------------------

function createSuccessOrder() {
    const customerId = pickCustomerId();
    const productId  = pickProductId();
    const quantity   = randomInt(1, 5); // Small quantity — should succeed

    const payload = JSON.stringify({
        customerId: customerId,
        lineItems: [{
            productId: productId,
            quantity: quantity,
            unitPrice: randomInt(10, 100) + 0.99,
        }],
    });

    const res = http.post(`${serviceUrl('order')}/api/orders`, payload, { headers: HEADERS });
    const ok = checkStatus(res, 201) || checkStatus(res, 200);
    orderSuccessDuration.add(res.timings.duration);
    demoErrors.add(!ok);
    if (ok) {
        sagaSuccessCount.add(1);
    }
}

function createFailOrder() {
    const customerId = pickCustomerId();
    const productId  = pickProductId();
    const quantity   = randomInt(99000, 99999); // Huge quantity — should fail stock reservation

    const payload = JSON.stringify({
        customerId: customerId,
        lineItems: [{
            productId: productId,
            quantity: quantity,
            unitPrice: 9.99,
        }],
    });

    const res = http.post(`${serviceUrl('order')}/api/orders`, payload, { headers: HEADERS });
    // Both 201 (order accepted, saga will compensate) and 4xx are valid outcomes
    orderFailDuration.add(res.timings.duration);
    sagaFailCount.add(1);
    demoErrors.add(res.status >= 500);
}

function getCustomerView() {
    const customerId = pickCustomerId();
    const res = http.get(`${serviceUrl('account')}/api/customers/${customerId}`, { headers: HEADERS });
    customerViewDuration.add(res.timings.duration);
    readCount.add(1);
    demoErrors.add(res.status >= 500);
}

function getProductView() {
    const productId = pickProductId();
    const res = http.get(`${serviceUrl('inventory')}/api/products/${productId}`, { headers: HEADERS });
    productViewDuration.add(res.timings.duration);
    readCount.add(1);
    demoErrors.add(res.status >= 500);
}

function createNewCustomer() {
    const payload = JSON.stringify({
        firstName: 'Demo',
        lastName: `Visitor-${randomInt(1, 9999)}`,
        email: `demo-${uuid().substring(0, 8)}@example.com`,
    });

    const res = http.post(`${serviceUrl('account')}/api/customers`, payload, { headers: HEADERS });
    customerCreateDuration.add(res.timings.duration);
    newCustomerCount.add(1);
    demoErrors.add(res.status >= 400);
}

// ---------------------------------------------------------------------------
// Main entry point — weighted random selection
// ---------------------------------------------------------------------------
export default function () {
    const roll = Math.random() * 100;

    if (roll < 50) {
        createSuccessOrder();      // 50%
    } else if (roll < 70) {
        getCustomerView();         // 20%
    } else if (roll < 85) {
        getProductView();          // 15%
    } else if (roll < 95) {
        createFailOrder();         // 10%
    } else {
        createNewCustomer();       //  5%
    }

    // Random think time — looks natural on dashboards
    sleep(randomInt(200, 1000) / 1000);
}
