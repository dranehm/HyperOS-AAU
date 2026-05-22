#!/bin/bash
# One command to prepare and run tonight's Xiaomi unlock attempt.
# Run any time before 18:00 CEST. Ctrl+C safe — shooters keep running.
cd "$(dirname "$0")/../.."
exec python3 tools/aggressive_unlock/prepare_attempt.py "$@"
