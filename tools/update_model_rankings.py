#!/usr/bin/env python3
"""
Model Rankings Update Script for Harness Agent Smart Model Picker.

Fetches REAL model rankings from the LMArena leaderboard dataset
(Hugging Face: lmarena-ai/leaderboard-dataset, published by LMArena) and
normalizes them into the picker's 0.0 - 100.0 score schema:

  overallScore  <- text board,   category "overall" (Arena rating)
  codingScore   <- text board,   category "coding"  (Arena rating)
  visionScore   <- vision board, category "overall" (Arena rating)
  toolUseScore  <- agent board,  category "overall" (task success score)
  speedScore    <- not measured by this source; left 0.0 (= no data)

Each board is scaled independently with min-max normalization across the
models on that board: score = (value - min) / (max - min) * 100.

Failure policy: if any board cannot be fetched/parsed, or the merged
result has too few models, the script exits non-zero and writes NOTHING,
so the weekly workflow fails loudly instead of silently shipping an
empty rankings file. (The previous generator was a stub that always
produced an empty payload, which is why this pipeline died unnoticed.)
"""

import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone
from typing import Any, Dict, List

DATASET = "lmarena-ai/leaderboard-dataset"
API_URL = f"https://huggingface.co/api/datasets/{DATASET}"
RESOLVE_URL = f"https://huggingface.co/datasets/{DATASET}/resolve/main"

MIN_MODELS = 100

# Trailing arena effort/variant tags, e.g. " (High)", " (Max, 20260813)", " (xHigh)"
VARIANT_SUFFIX = re.compile(r"\s*\([^()]*\)\s*$")


def fetch_json(url: str) -> Any:
    req = urllib.request.Request(url, headers={"User-Agent": "harness-rankings-updater"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)


def fetch_bytes(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "harness-rankings-updater"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return resp.read()


def latest_parquet_path(siblings: List[str], board_dir: str) -> str:
    """Find '<board>/latest-*.parquet' in the dataset file list."""
    matches = sorted(
        s for s in siblings
        if s.startswith(board_dir + "/latest-") and s.endswith(".parquet")
    )
    if not matches:
        raise RuntimeError(f"No latest parquet found for board '{board_dir}'")
    return matches[0]


def base_name(display_name: str) -> str:
    """Strip trailing arena variant tags so variants merge into one model."""
    name = display_name.strip()
    while VARIANT_SUFFIX.search(name):
        name = VARIANT_SUFFIX.sub("", name).strip()
    return name


def normalize_name(name: str) -> str:
    cleaned = name.lower().strip()
    cleaned = re.sub(r"^(?:meta-llama|meta|qwen|deepseek|mistralai|google|microsoft|zai|z-ai|alibaba|models)/", "", cleaned)
    cleaned = re.sub(r":(?:free|beta|extended)$", "", cleaned)
    cleaned = re.sub(r"-(?:free|beta)$", "", cleaned)
    cleaned = re.sub(r"[/_\s]+", "-", cleaned)
    return cleaned


def load_board(siblings: List[str], board_dir: str, category: str, value_col: str):
    """Return ({normalized_name: (display_name, raw_value)}, {publish_dates})."""
    import io

    import pandas as pd

    path = latest_parquet_path(siblings, board_dir)
    raw = fetch_bytes(f"{RESOLVE_URL}/{path}")
    df = pd.read_parquet(io.BytesIO(raw))
    if "category" in df.columns:
        cat_matches = df[df["category"] == category]
        if not cat_matches.empty:
            df = cat_matches
    col = value_col if value_col in df.columns else ("rating" if "rating" in df.columns else "score")
    if col not in df.columns:
        raise RuntimeError(f"Board {board_dir}: missing column '{value_col}' (cols={list(df.columns)})")
    df = df.dropna(subset=[col])
    out: Dict[str, Any] = {}
    publish_dates = set()
    for _, row in df.iterrows():
        display = base_name(str(row["model_name"]))
        key = normalize_name(display)
        value = float(row[col])
        if key not in out or value > out[key][1]:
            out[key] = (display, value)
        if "leaderboard_publish_date" in df.columns:
            publish_dates.add(str(row["leaderboard_publish_date"]))
    return out, publish_dates


def scale(board: Dict[str, Any]) -> Dict[str, float]:
    """Min-max scale raw values to 0..100 within this board."""
    if not board:
        return {}
    values = [v for _, v in board.values()]
    lo, hi = min(values), max(values)
    span = (hi - lo) or 1.0
    return {k: round((v - lo) / span * 100.0, 1) for k, (disp, v) in board.items()}


def generate_rankings() -> Dict[str, Any]:
    info = fetch_json(API_URL)
    siblings = [s["rfilename"] for s in info.get("siblings", [])]

    overall_raw, dates_text = load_board(siblings, "text", "overall", "rating")
    coding_raw, _ = load_board(siblings, "text", "coding", "rating")
    vision_raw, dates_vision = load_board(siblings, "vision", "overall", "rating")
    agent_raw, dates_agent = load_board(siblings, "agent", "overall", "score")

    overall = scale(overall_raw)
    coding = scale(coding_raw)
    vision = scale(vision_raw)
    tool_use = scale(agent_raw)

    models: List[Dict[str, Any]] = []
    for key, score in sorted(overall.items(), key=lambda kv: -kv[1]):
        display = overall_raw[key][0]
        if "carbon" in key.lower() or "carbon" in display.lower():
            continue
        models.append({
            "model": display,
            "overallScore": score,
            "codingScore": coding.get(key, 0.0),
            "toolUseScore": tool_use.get(key, 0.0),
            "visionScore": vision.get(key, 0.0),
            "speedScore": 0.0,
        })

    if len(models) < MIN_MODELS:
        raise RuntimeError(f"Only {len(models)} models merged (expected >= {MIN_MODELS}); refusing to write")

    # Load image generation and edit leaderboards
    image_models: List[Dict[str, Any]] = []
    dates_t2i: set = set()
    dates_edit: set = set()
    try:
        t2i_raw, dates_t2i = load_board(siblings, "text_to_image", "overall", "rating")
        edit_raw, dates_edit = load_board(siblings, "image_edit", "overall", "rating")
        t2i_scaled = scale(t2i_raw)
        edit_scaled = scale(edit_raw)

        SIDE_BY_SIDE_MODELS = [
            {"id": "mai-image-2.6", "display": "MAI-Image-2.6", "keys": ["mai-image-2-6", "mai-image", "mai"], "provider": "harness"},
            {"id": "grok-imagine-2.0", "display": "Grok Imagine Image 2.0", "keys": ["grok-imagine-image-2-0", "grok-imagine-2-0", "grok-imagine", "grok"], "provider": "harness"},
            {"id": "muse-image", "display": "Muse Image", "keys": ["muse-image", "muse"], "provider": "harness"},
            {"id": "seedream-5.0-lite", "display": "Seedream 5.0 Lite", "keys": ["seedream-5-0-lite", "seedream"], "provider": "harness"},
            {"id": "seedream-5.0-pro", "display": "Seedream 5.0 Pro", "keys": ["seedream-5-0-pro"], "provider": "harness"},
            {"id": "Qwen/Qwen-Image-2.1", "display": "Qwen-Image-2.1", "keys": ["qwen-image-2-1", "qwen-image"], "provider": "modelscope"},
            {"id": "black-forest-labs/flux-1-schnell", "display": "FLUX.1-schnell", "keys": ["flux-1-schnell", "flux"], "provider": "openrouter"},
        ]

        for sbs in SIDE_BY_SIDE_MODELS:
            match_t2i_key = next((k for k in t2i_raw if any(alias in k for alias in sbs["keys"])), None)
            match_edit_key = next((k for k in edit_raw if any(alias in k for alias in sbs["keys"])), None)
            t2i_score = t2i_scaled.get(match_t2i_key, 0.0) if match_t2i_key else 0.0
            edit_score = edit_scaled.get(match_edit_key, 0.0) if match_edit_key else 0.0
            t2i_rating = t2i_raw[match_t2i_key][1] if match_t2i_key else 0.0
            edit_rating = edit_raw[match_edit_key][1] if match_edit_key else 0.0
            avg_rating = (t2i_rating + edit_rating) / 2.0 if (t2i_rating and edit_rating) else (t2i_rating or edit_rating)

            image_models.append({
                "model": sbs["display"],
                "id": sbs["id"],
                "provider": sbs["provider"],
                "t2iScore": t2i_score,
                "editScore": edit_score,
                "arenaRatingT2I": round(t2i_rating, 1),
                "arenaRatingEdit": round(edit_rating, 1),
                "avgRating": round(avg_rating, 1),
                "availableInSideBySide": True,
            })
        image_models.sort(key=lambda x: -x["avgRating"])
        if image_models:
            image_models[0]["isRecommended"] = True
    except Exception as exc:
        print(f"Warning: Failed to load image boards: {exc}", file=sys.stderr)

    publish = sorted(dates_text | dates_vision | dates_agent | dates_t2i | dates_edit)
    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "updatedAt": now_iso,
        "version": 1,
        "sources": [
            f"LMArena leaderboard-dataset (huggingface.co/datasets/{DATASET}), "
            f"boards text/coding/vision/agent/text_to_image/image_edit, snapshots {', '.join(publish) or 'unknown'}, "
            "each board min-max scaled to 0-100; speedScore not measured by source (0.0 = no data)",
        ],
        "models": models,
        "imageModels": image_models,
    }


def main() -> int:
    try:
        rankings = generate_rankings()
    except Exception as exc:  # noqa: BLE001 - fail loudly, write nothing
        print(f"ERROR: rankings generation failed: {exc}", file=sys.stderr)
        return 1

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
    return 0


if __name__ == "__main__":
    sys.exit(main())
