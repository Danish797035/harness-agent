#!/usr/bin/env python3
"""
Model Rankings Update Script for Mina Smart Model Picker.

Fetches and normalizes model rankings from approved public leaderboards.
When no approved sources are active or available, generates an empty rankings payload
so the picker relies purely on live data (parameters, context, tools, probe, real usage).
"""

import json
import os
import re
import sys
from datetime import datetime, timezone
from typing import Dict, Any, List

def normalize_name(name: str) -> str:
    cleaned = name.lower().strip()
    cleaned = re.sub(r"^(?:meta-llama|meta|qwen|deepseek|mistralai|google|microsoft|zai|z-ai|alibaba|models)/", "", cleaned)
    cleaned = re.sub(r":(?:free|beta|extended)$", "", cleaned)
    cleaned = re.sub(r"-(?:free|beta)$", "", cleaned)
    cleaned = re.sub(r"[/_\s]+", "-", cleaned)
    return cleaned

def generate_rankings() -> Dict[str, Any]:
    # No hardcoded scores or unverified sources.
    # Dynamic leaderboards will be connected once approved.
    models: List[Dict[str, Any]] = []
    sources: List[str] = []

    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    return {
        "updatedAt": now_iso,
        "version": 1,
        "sources": sources,
        "models": models,
    }

def main():
    rankings = generate_rankings()
    out_json = json.dumps(rankings, indent=2)

    root_file = os.path.abspath("model-rankings.json")
    with open(root_file, "w", encoding="utf-8") as f:
        f.write(out_json + "\n")
    print(f"Wrote {root_file} ({len(rankings['models'])} models)")

    res_dir = os.path.abspath("core/src/main/resources")
    os.makedirs(res_dir, exist_ok=True)
    res_file = os.path.join(res_dir, "model-rankings.json")
    with open(res_file, "w", encoding="utf-8") as f:
        f.write(out_json + "\n")
    print(f"Wrote {res_file}")

if __name__ == "__main__":
    main()
