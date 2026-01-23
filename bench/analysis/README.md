# Bench Analysis

This directory contains a lightweight comparison script for bench JSON outputs.

## Setup (virtual environment)

From the repo root:

```
python3 -m venv bench/analysis/.venv
source bench/analysis/.venv/bin/activate
python bench/analysis/compare.py --help
```

The script uses only the Python standard library, so no dependencies are required.

## Usage

```
python bench/analysis/compare.py \
  --new bench/results/baseline-new.json \
  --legacy bench/results/baseline-legacy.json
```

It prints a comparison table with throughput, latency, publish stats, errors, phase coverage, and NWC metrics when present.
