#!/usr/bin/env python3
"""
Generate performance comparison charts for deployment-comparison.md.

Produces PNG charts showing throughput scaling, latency, and cost efficiency
across Local, AWS Small, AWS Medium, and AWS Large deployment tiers.

Usage:
    python3 docs/perf/charts/generate-charts.py
"""

import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
import os

CHART_DIR = os.path.dirname(os.path.abspath(__file__))

# Color palette
COLORS = {
    'local': '#4CAF50',
    'small': '#2196F3',
    'medium': '#FF9800',
    'large': '#E91E63',
    'ideal': '#9E9E9E',
    'clustered': '#7C4DFF',
}

TIER_LABELS = ['Local', 'AWS Small', 'AWS Medium', 'AWS Large']

# ============================================================
# Data from deployment-comparison.md
# ============================================================

# TPS levels tested per tier and achieved iter/s
data = {
    'local':  {'tps': [10, 25, 50],             'iters': [9.97, 24.95, 49.89]},
    'small':  {'tps': [10, 25, 50, 100],        'iters': [9.96, 24.87, 49.64, 99.16]},
    'medium': {'tps': [10, 25, 50, 100, 200],   'iters': [9.95, 24.89, 48.86, 94.12, 187.75]},
    'large':  {'tps': [10, 25, 50, 100, 200, 500], 'iters': [9.59, 24.02, 47.24, 94.59, 185.50, 274.07]},
}

# HTTP p95 latency (ms) per tier per TPS
latency_p95 = {
    'local':  {'tps': [10, 25, 50],             'p95': [66.01, 21.28, 19.50]},
    'small':  {'tps': [10, 25, 50, 100],        'p95': [742.82, 311.57, 141.03, 125.47]},
    'medium': {'tps': [10, 25, 50, 100, 200],   'p95': [132.74, 107.16, 87.60, 87.27, 99.05]},
    'large':  {'tps': [10, 25, 50, 100, 200, 500], 'p95': [114.21, 73.65, 68.11, 105.22, 144.85, 161.87]},
}

# Order create p95 (ms)
order_p95 = {
    'local':  {'tps': [10, 25, 50],             'p95': [91.48, 21.23, 19.58]},
    'small':  {'tps': [10, 25, 50, 100],        'p95': [809.72, 308.63, 141.48, 129.69]},
    'medium': {'tps': [10, 25, 50, 100, 200],   'p95': [129.24, 87.82, 85.95, 93.87, 106.77]},
    'large':  {'tps': [10, 25, 50, 100, 200, 500], 'p95': [115.30, 82.36, 73.73, 115.19, 154.28, 169.49]},
}

# Cost per hour
cost_per_hour = {'local': 0, 'small': 0.76, 'medium': 1.18, 'large': 3.50}

# CAS overhead data (from A/B test)
cas_overhead = {
    'metrics': ['HTTP\np50', 'HTTP\np95', 'Order\np50', 'Order\np95', 'Saga E2E\np50', 'Saga E2E\np95'],
    'baseline': [16.46, 31.92, 16.81, 34.50, 219.00, 238.40],
    'clustered': [16.39, 38.77, 16.74, 46.89, 220.00, 268.35],
}

# Sustained test memory growth
sustained_memory = {
    'services': ['order', 'inventory', 'payment', 'account'],
    'start_mib': [566, 528, 485, 572],
    'end_mib': [1114, 821, 782, 681],
    'limit_mib': [1536, 1024, 1024, 1024],
}


def setup_style():
    """Apply consistent chart styling."""
    plt.rcParams.update({
        'figure.facecolor': 'white',
        'axes.facecolor': '#FAFAFA',
        'axes.grid': True,
        'grid.alpha': 0.3,
        'grid.linestyle': '--',
        'font.size': 11,
        'axes.titlesize': 14,
        'axes.titleweight': 'bold',
        'axes.labelsize': 12,
        'legend.fontsize': 10,
        'figure.dpi': 150,
    })


def chart_throughput_scaling():
    """Chart 1: Throughput scaling across tiers with ideal-linear reference."""
    fig, ax = plt.subplots(figsize=(10, 6))

    # Ideal linear scaling reference
    ideal_tps = np.array([10, 25, 50, 100, 200, 500])
    ax.plot(ideal_tps, ideal_tps, '--', color=COLORS['ideal'], linewidth=2,
            label='Ideal (1:1)', zorder=1)

    for tier, color, marker in [
        ('local', COLORS['local'], 'o'),
        ('small', COLORS['small'], 's'),
        ('medium', COLORS['medium'], 'D'),
        ('large', COLORS['large'], '^'),
    ]:
        d = data[tier]
        ax.plot(d['tps'], d['iters'], '-' + marker, color=color, linewidth=2,
                markersize=8, label=f'{tier.capitalize()}', zorder=2)

    ax.set_xlabel('Target TPS')
    ax.set_ylabel('Achieved Iterations/s')
    ax.set_title('Throughput Scaling: Target vs. Achieved TPS')
    ax.legend(loc='upper left')
    ax.set_xlim(0, 520)
    ax.set_ylim(0, 520)
    ax.set_aspect('equal')

    # Annotate Large ceiling
    ax.annotate('k6 200 VU limit\n(274 achieved at 500 target)',
                xy=(500, 274), xytext=(350, 150),
                arrowprops=dict(arrowstyle='->', color=COLORS['large']),
                fontsize=9, color=COLORS['large'])

    plt.tight_layout()
    plt.savefig(os.path.join(CHART_DIR, 'throughput-scaling.png'))
    plt.close()
    print('  throughput-scaling.png')


def chart_latency_by_tier():
    """Chart 2: HTTP p95 latency across TPS levels per tier."""
    fig, ax = plt.subplots(figsize=(10, 6))

    for tier, color, marker in [
        ('local', COLORS['local'], 'o'),
        ('small', COLORS['small'], 's'),
        ('medium', COLORS['medium'], 'D'),
        ('large', COLORS['large'], '^'),
    ]:
        d = latency_p95[tier]
        ax.plot(d['tps'], d['p95'], '-' + marker, color=color, linewidth=2,
                markersize=8, label=f'{tier.capitalize()}')

    ax.set_xlabel('Target TPS')
    ax.set_ylabel('HTTP Request Duration p95 (ms)')
    ax.set_title('P95 Latency vs. Load (All Endpoints)')
    ax.legend(loc='upper right')
    ax.set_xscale('log')
    ax.set_xticks([10, 25, 50, 100, 200, 500])
    ax.get_xaxis().set_major_formatter(ticker.ScalarFormatter())
    ax.set_ylim(0, 800)

    # Annotate Small warmup
    ax.annotate('Cold-start overhead\n(JVM warmup at 10 TPS)',
                xy=(10, 742), xytext=(30, 650),
                arrowprops=dict(arrowstyle='->', color=COLORS['small']),
                fontsize=9, color=COLORS['small'])

    plt.tight_layout()
    plt.savefig(os.path.join(CHART_DIR, 'latency-by-tier.png'))
    plt.close()
    print('  latency-by-tier.png')


def chart_cost_efficiency():
    """Chart 3: Cost per 10K transactions at max tested TPS."""
    fig, ax = plt.subplots(figsize=(8, 5))

    tiers = ['AWS Small\n(100 TPS)', 'AWS Medium\n(200 TPS)', 'AWS Large\n(274 iter/s)']
    costs = [0.021, 0.016, 0.036]
    colors = [COLORS['small'], COLORS['medium'], COLORS['large']]

    bars = ax.bar(tiers, costs, color=colors, width=0.5, edgecolor='white', linewidth=2)

    for bar, cost in zip(bars, costs):
        ax.text(bar.get_x() + bar.get_width() / 2., bar.get_height() + 0.001,
                f'${cost:.3f}', ha='center', va='bottom', fontweight='bold', fontsize=12)

    ax.set_ylabel('Cost per 10K Transactions ($)')
    ax.set_title('Cost Efficiency at Max Tested TPS')
    ax.set_ylim(0, 0.05)
    ax.axhline(y=costs[1], color=COLORS['medium'], linestyle=':', alpha=0.5)

    plt.tight_layout()
    plt.savefig(os.path.join(CHART_DIR, 'cost-efficiency.png'))
    plt.close()
    print('  cost-efficiency.png')


def chart_cas_overhead():
    """Chart 4: CAS clustering overhead comparison (baseline vs clustered)."""
    fig, ax = plt.subplots(figsize=(10, 5))

    x = np.arange(len(cas_overhead['metrics']))
    width = 0.35

    bars1 = ax.bar(x - width/2, cas_overhead['baseline'], width,
                   label='Baseline (no clustering)', color=COLORS['local'],
                   edgecolor='white', linewidth=1.5)
    bars2 = ax.bar(x + width/2, cas_overhead['clustered'], width,
                   label='Clustered (CAS + AT_LEAST_ONCE)', color=COLORS['clustered'],
                   edgecolor='white', linewidth=1.5)

    ax.set_ylabel('Latency (ms)')
    ax.set_title('CAS Overhead: Baseline vs. Single-Replica Clustered (50 TPS)')
    ax.set_xticks(x)
    ax.set_xticklabels(cas_overhead['metrics'], fontsize=10)
    ax.legend(loc='upper left')

    # Add percentage labels on clustered bars
    for b1, b2 in zip(bars1, bars2):
        pct = ((b2.get_height() - b1.get_height()) / b1.get_height()) * 100
        color = '#D32F2F' if pct > 5 else '#4CAF50'
        sign = '+' if pct > 0 else ''
        ax.text(b2.get_x() + b2.get_width() / 2., b2.get_height() + 2,
                f'{sign}{pct:.0f}%', ha='center', va='bottom', fontsize=9,
                color=color, fontweight='bold')

    plt.tight_layout()
    plt.savefig(os.path.join(CHART_DIR, 'cas-overhead.png'))
    plt.close()
    print('  cas-overhead.png')


def chart_sustained_memory():
    """Chart 5: Memory growth during 30-minute sustained load test."""
    fig, ax = plt.subplots(figsize=(9, 5))

    x = np.arange(len(sustained_memory['services']))
    width = 0.25

    bars_start = ax.bar(x - width, sustained_memory['start_mib'], width,
                        label='Start (1 min)', color='#81D4FA',
                        edgecolor='white', linewidth=1.5)
    bars_end = ax.bar(x, sustained_memory['end_mib'], width,
                      label='End (30 min)', color='#E57373',
                      edgecolor='white', linewidth=1.5)
    bars_limit = ax.bar(x + width, sustained_memory['limit_mib'], width,
                        label='Container limit', color='#BDBDBD',
                        edgecolor='white', linewidth=1.5)

    # Add percentage of limit labels
    for i, (end, limit) in enumerate(zip(sustained_memory['end_mib'],
                                          sustained_memory['limit_mib'])):
        pct = (end / limit) * 100
        ax.text(i, end + 20, f'{pct:.0f}%', ha='center', va='bottom',
                fontsize=10, fontweight='bold',
                color='#D32F2F' if pct > 70 else '#4CAF50')

    ax.set_ylabel('Memory (MiB)')
    ax.set_title('Memory Growth: 30-Minute Sustained Load @ 50 TPS (Clustering Enabled)')
    ax.set_xticks(x)
    ax.set_xticklabels([s.replace('-', '\n') for s in
                        ['order-service', 'inventory-service', 'payment-service', 'account-service']],
                       fontsize=10)
    ax.legend(loc='upper right')
    ax.set_ylim(0, 1700)

    plt.tight_layout()
    plt.savefig(os.path.join(CHART_DIR, 'sustained-memory.png'))
    plt.close()
    print('  sustained-memory.png')


def chart_order_create_scaling():
    """Chart 6: Order create p95 latency scaling — the key saga operation."""
    fig, ax = plt.subplots(figsize=(10, 6))

    for tier, color, marker in [
        ('local', COLORS['local'], 'o'),
        ('small', COLORS['small'], 's'),
        ('medium', COLORS['medium'], 'D'),
        ('large', COLORS['large'], '^'),
    ]:
        d = order_p95[tier]
        ax.plot(d['tps'], d['p95'], '-' + marker, color=color, linewidth=2,
                markersize=8, label=f'{tier.capitalize()}')

    ax.set_xlabel('Target TPS')
    ax.set_ylabel('Order Create Duration p95 (ms)')
    ax.set_title('Order Create P95 Latency vs. Load')
    ax.legend(loc='upper right')
    ax.set_xscale('log')
    ax.set_xticks([10, 25, 50, 100, 200, 500])
    ax.get_xaxis().set_major_formatter(ticker.ScalarFormatter())
    ax.set_ylim(0, 900)

    # Shade the "acceptable" zone
    ax.axhspan(0, 200, alpha=0.08, color='green', label='_nolegend_')
    ax.text(480, 180, '<200ms', fontsize=9, color='green', ha='right', style='italic')

    plt.tight_layout()
    plt.savefig(os.path.join(CHART_DIR, 'order-create-scaling.png'))
    plt.close()
    print('  order-create-scaling.png')


if __name__ == '__main__':
    setup_style()
    print('Generating performance charts...')
    chart_throughput_scaling()
    chart_latency_by_tier()
    chart_cost_efficiency()
    chart_cas_overhead()
    chart_sustained_memory()
    chart_order_create_scaling()
    print(f'Done. Charts saved to {CHART_DIR}/')
