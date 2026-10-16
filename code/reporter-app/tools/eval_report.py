#!/usr/bin/env python3
"""Append an evaluation run to doc/EVAL_RESULTS.md (newest run first).

Input: the results.json written by the app's "Run eval set" (developer menu) on the device, pulled with
(see eval/README.md):

    adb exec-out run-as com.example.test_app cat files/eval_runs/<run id>/results.json > results.json

Usage:

    python tools/eval_report.py results.json
    python tools/eval_report.py results.json --doc doc/EVAL_RESULTS.md --commit abc1234
    python tools/eval_report.py results.json --dry-run          # print the section, change nothing

The new section goes right below the `<!-- eval_report:insert -->` marker, i.e. ABOVE all older runs, and the
"No device run yet" placeholder is removed. It contains the date, device, model versions, git commit hash and one
table per metric (ASR, extraction, mapping, dialogue, rephraser, latency, prompt size) plus a per-case table.
Only numbers found in results.json are printed; nothing is estimated. Exit codes: 0 ok, 1 bad input, 2 the run is
already in the document.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

MARKER = "<!-- eval_report:insert -->"
PLACEHOLDER = re.compile(r"^No device run yet\b.*$", re.IGNORECASE)


def pct(num: float | int | None, den: float | int | None = None) -> str:
    if num is None:
        return "n/a"
    if den is not None:
        if den == 0:
            return "n/a"
        num = num / den
    return f"{num * 100:.1f}%"


def ms(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.0f}"


def num(value: float | None, digits: int = 2) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}"


def table(headers: list[str], rows: list[list[str]]) -> list[str]:
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    out += ["| " + " | ".join(str(c).replace("|", "/") for c in r) + " |" for r in rows]
    return out


def git_commit(repo: Path) -> str:
    try:
        r = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=True, timeout=20
        )
        dirty = subprocess.run(
            ["git", "-C", str(repo), "status", "--porcelain"], capture_output=True, text=True, check=True, timeout=20
        ).stdout.strip()
        return r.stdout.strip() + (" (+ uncommitted changes)" if dirty else "")
    except (OSError, subprocess.SubprocessError):
        return "unknown (not a git checkout)"


def stats_row(name: str, s: dict | None) -> list[str]:
    if not s:
        return [name, "0", "n/a", "n/a", "n/a", "n/a", "n/a"]
    return [name, str(s["count"]), ms(s["median"]), ms(s["p90"]), ms(s["mean"]), ms(s["min"]), ms(s["max"])]


def case_wer(case: dict) -> str:
    errors = refs = 0
    for u in case.get("utterances", []):
        w = u.get("wer")
        if w:
            errors += w["substitutions"] + w["deletions"] + w["insertions"]
            refs += w["reference_words"]
    return pct(errors, refs) if refs else "n/a"


def render(run: dict, commit: str, device: str | None, date: str | None, note: str | None = None) -> str:
    meta = run["meta"]
    s = run["summary"]
    started = run.get("started_at", "")
    day = date or (started[:10] if started else datetime.now().strftime("%Y-%m-%d"))
    dev = device or meta.get("device", "unknown")
    out: list[str] = [f"## {day} - {dev} - run {run['run_id']}", ""]
    out += [
        f"- Date: {day} (run started {started})",
        f"- Device: {dev}, Android {meta.get('android_version', 'unknown')}",
        f"- App version: {meta.get('app_version', 'unknown')}; git commit: {commit}",
        f"- Input: {run['input']} ({'typed transcripts, no speech recognition' if run['input'] == 'transcript' else 'WAV files through the speech-to-text service'})",
        f"- AI mode: {meta.get('ai_mode')}; LLM: {meta.get('llm_model')}; ASR model: {meta.get('asr_model')}; "
        f"rephraser: {'on' if meta.get('rephrase_enabled') else 'off'}",
        f"- Cases: {s['cases_total']} (ok {s['cases_ok']}, error {s['cases_error']}, skipped {s['cases_skipped']})",
        *([f"- Note: {note}"] if note else []),
        "",
    ]
    if meta.get("ai_mode") == "mock":
        out += ["> **Mock AI run: extraction is scripted, so the extraction, mapping and dialogue numbers say nothing about the models.**", ""]

    asr = s["asr"]
    out += ["**ASR (word error rate)**", ""]
    if asr["available"]:
        out += table(
            ["Cases", "Utterances", "Mean WER", "Corpus WER"],
            [[asr["cases"], asr["utterances"], pct(asr["mean_wer"]), pct(asr["corpus_wer"])]],
        )
    else:
        out += ["WER: n/a (no ASR)"]
    out += [""]

    e = s["extraction"]
    out += ["**Extraction (field accuracy; exact / normalised / relaxed match)**", ""]
    rows = [
        [f, a["total"], a["matched"], a["exact"], a["normalized"], a["relaxed"], pct(a["matched"], a["total"])]
        for f, a in e["per_field"].items()
    ]
    rows.append(["**all**", e["total"], e["matched"], e["exact"], e["normalized"], e["relaxed"], pct(e["matched"], e["total"])])
    out += table(["Field", "Expected", "Matched", "Exact", "Normalised", "Relaxed", "Accuracy"], rows)
    out += ["", f"Fields that had to stay empty: {e['empty_correct']}/{e['empty_checks']} were empty.", ""]

    m = s["mapping"]
    out += ["**Mapping (value in the right field)**", ""]
    out += table(
        ["Correct", "Misplaced", "Wrong", "Missing", "Placement rate"],
        [[m["correct"], m["misplaced"], m["wrong"], m["missing"], pct(m["placement_rate"])]],
    )
    out += [""]

    d = s["dialogue"]
    out += ["**Dialogue**", ""]
    out += table(
        ["Reports", "Questions per report", "Max questions", "Mode accuracy", "Asked fields ok", "Outcomes ok", "Flow ok",
         "Ask-once violations", "Invariant violations"],
        [[
            d["reports"], num(d["questions_per_report"]), d["questions_max"], f"{d['mode_correct']}/{d['mode_total']}",
            f"{d['asked_ok']}/{d['asked_total']}", f"{d['outcome_correct']}/{d['outcome_total']}",
            f"{d['flow_ok']}/{d['flow_total']}", d["ask_once_violations"], d["invariant_violations"],
        ]],
    )
    mix = d["outcome_mix"]
    outcomes = sorted({o for v in mix.values() for o in v})
    if outcomes:
        out += ["", "Outcome mix per asked field:", ""]
        out += table(["Field"] + outcomes, [[f] + [v.get(o, 0) for o in outcomes] for f, v in mix.items()])
    out += [""]

    r = s["rephraser"]
    reasons = ", ".join(f"{k} x{v}" for k, v in r["rejection_reasons"].items()) or "-"
    lat = r.get("latency")
    out += ["**Rephraser**", ""]
    out += table(
        ["Attempts", "Validator pass rate", "Rejection reasons", "Latency median / p90 ms"],
        [[r["attempts"], pct(r["pass_rate"]), reasons, f"{ms(lat['median'])} / {ms(lat['p90'])}" if lat else "n/a"]],
    )
    out += [""]

    l = s["latency"]
    out += ["**Latency (ms; median and nearest-rank p90)**", ""]
    out += table(
        ["Stage", "Count", "Median", "p90", "Mean", "Min", "Max"],
        [
            stats_row("ASR", l.get("asr")),
            stats_row("Prefill", l.get("prefill")),
            stats_row("Decode", l.get("decode")),
            stats_row("Total (end of speech to filled form)", l.get("total")),
        ],
    )
    out += [""]

    out += [
        "**Prompt size**",
        "",
        "Not measured by the eval run. Tokens per call type are measured with Qwen's tokenizer by `tools/count_tokens.py` and "
        "recorded in `doc/PROMPTS.md`.",
        "",
    ]

    out += ["**Cases**", ""]
    rows = []
    for c in run["cases"]:
        matched = sum(1 for f in c.get("fields", []) if f["match"] != "NONE")
        total = len(c.get("fields", []))
        if c["status"] == "ok":
            mode = str(c.get("mode"))
            if c.get("expected_mode"):
                mode += " ok" if c["expected_mode"] == c.get("mode") else f" (expected {c['expected_mode']})"
            asked = ",".join(c.get("asked", [])) or "-"
            exp = c.get("expected_asked")
            inc, exc = c.get("asked_includes", []), c.get("asked_excludes", [])
            if exp is not None or inc or exc:
                ok = (exp is None or exp == c.get("asked", [])) and all(f in c["asked"] for f in inc) and not any(f in c["asked"] for f in exc)
                asked += " ok" if ok else " MISMATCH"
            note = "; ".join(c.get("invariant_violations", []))
        else:
            mode, asked, note = "-", "-", c.get("detail") or ""
        rows.append([c["id"], c["status"], mode, asked, f"{matched}/{total}", case_wer(c), c.get("ask_once_violations", 0), note])
    out += table(["Case", "Status", "Mode", "Asked", "Fields matched", "WER", "Ask-once", "Notes"], rows)
    out += [""]
    return "\n".join(out)


def insert(doc: str, section: str, run_id: str, force: bool) -> str:
    if f"run {run_id}" in doc and not force:
        raise SystemExit(f"error: run {run_id} is already in the document (use --force to add it again)")
    lines = doc.splitlines()
    try:
        at = next(i for i, line in enumerate(lines) if line.strip() == MARKER)
    except StopIteration:
        raise SystemExit(f"error: marker {MARKER} not found in the document") from None
    rest = lines[at + 1 :]
    while rest and (not rest[0].strip() or PLACEHOLDER.match(rest[0].strip())):
        rest.pop(0)  # drop the placeholder and blank lines before the first older run
    new = lines[: at + 1] + [""] + section.rstrip("\n").splitlines() + [""] + rest
    return "\n".join(new).rstrip("\n") + "\n"


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("results", type=Path, help="results.json pulled from the device")
    p.add_argument("--doc", type=Path, default=Path(__file__).resolve().parent.parent / "doc" / "EVAL_RESULTS.md")
    p.add_argument("--commit", help="git commit hash to record (default: git rev-parse --short HEAD of the repo)")
    p.add_argument("--device", help="device name to record (default: the one in results.json)")
    p.add_argument("--date", help="date to record, YYYY-MM-DD (default: the run's start date)")
    p.add_argument("--dry-run", action="store_true", help="print the section instead of writing the document")
    p.add_argument("--force", action="store_true", help="add the run even if its id is already in the document")
    p.add_argument("--note", help="free text recorded with the run, e.g. 'speaker B, noisy room'")
    args = p.parse_args()

    try:
        run = json.loads(args.results.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"error: cannot read {args.results}: {exc}", file=sys.stderr)
        return 1
    if run.get("schema") != "eval_results/1":
        print(f"error: {args.results} is not an eval_results/1 file (schema = {run.get('schema')!r})", file=sys.stderr)
        return 1

    commit = args.commit or git_commit(Path(__file__).resolve().parent.parent)
    section = render(run, commit, args.device, args.date, args.note)
    if args.dry_run:
        print(section)
        return 0
    try:
        doc = args.doc.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"error: cannot read {args.doc}: {exc}", file=sys.stderr)
        return 1
    try:
        updated = insert(doc, section, run["run_id"], args.force)
    except SystemExit as exc:
        print(exc, file=sys.stderr)
        return 2
    args.doc.write_text(updated, encoding="utf-8", newline="\n")
    print(f"added run {run['run_id']} to {args.doc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
