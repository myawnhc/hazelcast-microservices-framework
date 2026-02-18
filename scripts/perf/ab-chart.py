#!/usr/bin/env python3
"""
ab-chart.py - Optional PNG chart generator for A/B test comparisons.

Generates bar charts comparing latency, throughput, and error rates between
two k6 result JSON files using matplotlib. If matplotlib is not installed,
prints installation instructions and exits successfully.

Usage:
    python3 scripts/perf/ab-chart.py <result-a.json> <result-b.json> [options]

Options:
    --label-a NAME     Label for variant A (default: "Variant A")
    --label-b NAME     Label for variant B (default: "Variant B")
    --output-dir DIR   Directory for chart PNGs (default: ./charts)
    --help             Show this help message
"""

import json
import os
import sys

def print_usage():
    print("Usage: python3 ab-chart.py <result-a.json> <result-b.json> [options]")
    print()
    print("Options:")
    print("  --label-a NAME     Label for variant A (default: 'Variant A')")
    print("  --label-b NAME     Label for variant B (default: 'Variant B')")
    print("  --output-dir DIR   Directory for chart PNGs (default: ./charts)")
    print("  --help             Show this help message")

def extract_metric(data, metric_name, stat):
    """Extract a metric value from k6 JSON data."""
    metrics = data.get("metrics", {})
    metric = metrics.get(metric_name, {})
    values = metric.get("values", {})
    return values.get(stat)

def main():
    # Parse arguments
    args = sys.argv[1:]
    file_a = None
    file_b = None
    label_a = "Variant A"
    label_b = "Variant B"
    output_dir = "./charts"

    i = 0
    while i < len(args):
        if args[i] == "--help" or args[i] == "-h":
            print_usage()
            sys.exit(0)
        elif args[i] == "--label-a":
            label_a = args[i + 1]
            i += 2
        elif args[i] == "--label-b":
            label_b = args[i + 1]
            i += 2
        elif args[i] == "--output-dir":
            output_dir = args[i + 1]
            i += 2
        elif not args[i].startswith("-"):
            if file_a is None:
                file_a = args[i]
            elif file_b is None:
                file_b = args[i]
            i += 1
        else:
            print(f"Unknown option: {args[i]}")
            sys.exit(1)

    if not file_a or not file_b:
        print("Error: Two result JSON files are required.")
        print_usage()
        sys.exit(1)

    # Check matplotlib availability
    try:
        import matplotlib
        matplotlib.use('Agg')  # Non-interactive backend
        import matplotlib.pyplot as plt
        import matplotlib.ticker as ticker
    except ImportError:
        print("matplotlib is not installed. Charts will not be generated.")
        print()
        print("To install matplotlib:")
        print("  pip3 install matplotlib")
        print("  # or")
        print("  brew install python-matplotlib")
        print()
        print("The A/B comparison report (ab-compare.sh) includes ASCII charts as a fallback.")
        sys.exit(0)

    # Load data
    with open(file_a) as f:
        data_a = json.load(f)
    with open(file_b) as f:
        data_b = json.load(f)

    os.makedirs(output_dir, exist_ok=True)

    # Color scheme
    color_a = '#4285F4'  # Google Blue
    color_b = '#EA4335'  # Google Red

    # ---- Chart 1: P95 Latency Comparison ----
    metrics = [
        ("http_req_duration", "HTTP Overall"),
        ("order_create_duration", "Order Create"),
        ("stock_reserve_duration", "Stock Reserve"),
        ("customer_create_duration", "Customer Create"),
    ]

    labels = []
    vals_a = []
    vals_b = []
    for metric_key, display_name in metrics:
        p95_a = extract_metric(data_a, metric_key, "p(95)")
        p95_b = extract_metric(data_b, metric_key, "p(95)")
        if p95_a is not None or p95_b is not None:
            labels.append(display_name)
            vals_a.append(p95_a or 0)
            vals_b.append(p95_b or 0)

    if labels:
        fig, ax = plt.subplots(figsize=(10, 6))
        x = range(len(labels))
        width = 0.35

        bars_a = ax.bar([xi - width/2 for xi in x], vals_a, width, label=label_a, color=color_a)
        bars_b = ax.bar([xi + width/2 for xi in x], vals_b, width, label=label_b, color=color_b)

        ax.set_ylabel('Latency (ms)')
        ax.set_title('P95 Latency Comparison')
        ax.set_xticks(list(x))
        ax.set_xticklabels(labels, rotation=15, ha='right')
        ax.legend()

        # Add value labels on bars
        for bar in bars_a:
            height = bar.get_height()
            if height > 0:
                ax.annotate(f'{height:.1f}',
                            xy=(bar.get_x() + bar.get_width() / 2, height),
                            xytext=(0, 3), textcoords="offset points",
                            ha='center', va='bottom', fontsize=8)
        for bar in bars_b:
            height = bar.get_height()
            if height > 0:
                ax.annotate(f'{height:.1f}',
                            xy=(bar.get_x() + bar.get_width() / 2, height),
                            xytext=(0, 3), textcoords="offset points",
                            ha='center', va='bottom', fontsize=8)

        ax.yaxis.set_major_formatter(ticker.FormatStrFormatter('%.1f'))
        plt.tight_layout()
        chart_path = os.path.join(output_dir, "p95-latency-comparison.png")
        plt.savefig(chart_path, dpi=150)
        plt.close()
        print(f"  Chart saved: {chart_path}")

    # ---- Chart 2: Throughput Comparison ----
    iter_rate_a = extract_metric(data_a, "iterations", "rate") or 0
    iter_rate_b = extract_metric(data_b, "iterations", "rate") or 0
    http_rate_a = extract_metric(data_a, "http_reqs", "rate") or 0
    http_rate_b = extract_metric(data_b, "http_reqs", "rate") or 0

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(10, 5))

    # Iterations/s
    bars = ax1.bar([label_a, label_b], [iter_rate_a, iter_rate_b], color=[color_a, color_b])
    ax1.set_title('Iterations per Second')
    ax1.set_ylabel('iter/s')
    for bar in bars:
        height = bar.get_height()
        ax1.annotate(f'{height:.1f}',
                     xy=(bar.get_x() + bar.get_width() / 2, height),
                     xytext=(0, 3), textcoords="offset points",
                     ha='center', va='bottom', fontsize=9)

    # HTTP req/s
    bars = ax2.bar([label_a, label_b], [http_rate_a, http_rate_b], color=[color_a, color_b])
    ax2.set_title('HTTP Requests per Second')
    ax2.set_ylabel('req/s')
    for bar in bars:
        height = bar.get_height()
        ax2.annotate(f'{height:.1f}',
                     xy=(bar.get_x() + bar.get_width() / 2, height),
                     xytext=(0, 3), textcoords="offset points",
                     ha='center', va='bottom', fontsize=9)

    plt.tight_layout()
    chart_path = os.path.join(output_dir, "throughput-comparison.png")
    plt.savefig(chart_path, dpi=150)
    plt.close()
    print(f"  Chart saved: {chart_path}")

    # ---- Chart 3: Error Rate Comparison ----
    http_fail_a = (extract_metric(data_a, "http_req_failed", "rate") or 0) * 100
    http_fail_b = (extract_metric(data_b, "http_req_failed", "rate") or 0) * 100
    mixed_err_a = (extract_metric(data_a, "mixed_workload_errors", "rate") or 0) * 100
    mixed_err_b = (extract_metric(data_b, "mixed_workload_errors", "rate") or 0) * 100

    fig, ax = plt.subplots(figsize=(8, 5))
    err_labels = ['HTTP Failures', 'Mixed Workload Errors']
    err_a = [http_fail_a, mixed_err_a]
    err_b = [http_fail_b, mixed_err_b]

    x = range(len(err_labels))
    width = 0.35
    bars_a = ax.bar([xi - width/2 for xi in x], err_a, width, label=label_a, color=color_a)
    bars_b = ax.bar([xi + width/2 for xi in x], err_b, width, label=label_b, color=color_b)

    ax.set_ylabel('Error Rate (%)')
    ax.set_title('Error Rate Comparison')
    ax.set_xticks(list(x))
    ax.set_xticklabels(err_labels)
    ax.legend()
    ax.yaxis.set_major_formatter(ticker.FormatStrFormatter('%.1f%%'))

    for bar in bars_a:
        height = bar.get_height()
        if height > 0:
            ax.annotate(f'{height:.1f}%',
                        xy=(bar.get_x() + bar.get_width() / 2, height),
                        xytext=(0, 3), textcoords="offset points",
                        ha='center', va='bottom', fontsize=8)
    for bar in bars_b:
        height = bar.get_height()
        if height > 0:
            ax.annotate(f'{height:.1f}%',
                        xy=(bar.get_x() + bar.get_width() / 2, height),
                        xytext=(0, 3), textcoords="offset points",
                        ha='center', va='bottom', fontsize=8)

    plt.tight_layout()
    chart_path = os.path.join(output_dir, "error-rate-comparison.png")
    plt.savefig(chart_path, dpi=150)
    plt.close()
    print(f"  Chart saved: {chart_path}")

    print(f"\nAll charts saved to: {output_dir}/")

if __name__ == "__main__":
    main()
