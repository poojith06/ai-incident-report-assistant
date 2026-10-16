#!/usr/bin/env python3
"""Counts Qwen tokens of the rendered prompts and checks the build-prompt section 4 budget.

The prompts are written by `PromptBudgetTest` (the Kotlin `Prompts` object is the single source of truth) to
`app/build/rendered_prompts/{extraction,update,rephrase}.txt`. Chat-template tokens are part of the files, so the
counts include them.

Exit codes: 0 = all within hard caps, 1 = a hard cap is exceeded, 2 = a file or the tokenizer is missing.
"""
import argparse
import sys
from pathlib import Path

# name -> (target tokens, hard cap tokens)
BUDGET = {
    "extraction": (1200, 2000),
    "update": (800, 1500),
    "rephrase": (300, 500),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tokenizer", required=True, help="path to the Qwen tokenizer.json")
    parser.add_argument("--prompts", default="app/build/rendered_prompts", help="directory with extraction.txt, update.txt, rephrase.txt")
    args = parser.parse_args()

    tokenizer_path = Path(args.tokenizer)
    prompts_dir = Path(args.prompts)
    if not tokenizer_path.is_file():
        print(f"tokenizer not found: {tokenizer_path}", file=sys.stderr)
        return 2
    missing = [name for name in BUDGET if not (prompts_dir / f"{name}.txt").is_file()]
    if missing:
        print(f"missing rendered prompts in {prompts_dir}: {', '.join(m + '.txt' for m in missing)}"
              " (run the unit test PromptBudgetTest first)", file=sys.stderr)
        return 2

    try:
        from tokenizers import Tokenizer
    except ImportError:
        print("the 'tokenizers' package is required: pip install tokenizers", file=sys.stderr)
        return 2
    tokenizer = Tokenizer.from_file(str(tokenizer_path))

    print(f"{'prompt':<12}{'tokens':>8}{'target':>8}{'hard cap':>10}  status")
    exceeded = False
    for name, (target, cap) in BUDGET.items():
        text = (prompts_dir / f"{name}.txt").read_text(encoding="utf-8")
        tokens = len(tokenizer.encode(text).ids)
        if tokens > cap:
            status = "OVER HARD CAP"
            exceeded = True
        elif tokens > target:
            status = "over target"
        else:
            status = "ok"
        print(f"{name:<12}{tokens:>8}{target:>8}{cap:>10}  {status}")
    return 1 if exceeded else 0


if __name__ == "__main__":
    sys.exit(main())
