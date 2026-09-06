from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend"


def replace_function(text: str, function_name: str, replacement: str) -> str:
    lines = text.splitlines(keepends=True)
    start = next((i for i, line in enumerate(lines) if line.startswith(f"def {function_name}(")), None)
    if start is None:
        raise RuntimeError(f"Function {function_name} not found")
    end = start + 1
    while end < len(lines):
        line = lines[end]
        if line.strip() and not line.startswith((" ", "\t")):
            break
        end += 1
    replacement_lines = [line + "\n" for line in replacement.rstrip("\n").split("\n")]
    return "".join(lines[:start] + replacement_lines + ["\n"] + lines[end:])


def ensure_import(text: str, import_line: str, after_prefixes: tuple[str, ...]) -> str:
    if import_line in text:
        return text
    lines = text.splitlines(keepends=True)
    candidate = None
    for i, line in enumerate(lines):
        if any(line.startswith(prefix) for prefix in after_prefixes):
            candidate = i
    if candidate is None:
        raise RuntimeError(f"Import anchor not found for {import_line.strip()}")
    lines.insert(candidate + 1, import_line)
    return "".join(lines)


def patch_main() -> bool:
    path = BACKEND / "main.py"
    text = path.read_text()
    original = text
    text = ensure_import(
        text,
        "from auth import router as auth_router, require_access\n",
        ("from openai import", "from team import", "from knowledge import", "from crawler import"),
    )
    text = replace_function(
        text,
        "_require_admin",
        "def _require_admin(token: str | None) -> None:\n    require_access(token)",
    )
    if "app.include_router(auth_router)" not in text:
        anchor = 'app = FastAPI(title="JARVIS Watch Bridge API", version="0.6.0")\n'
        if anchor not in text:
            raise RuntimeError("FastAPI app anchor not found")
        text = text.replace(anchor, anchor + "app.include_router(auth_router)\n", 1)
    if text != original:
        path.write_text(text)
        return True
    return False


def patch_module(name: str) -> bool:
    path = BACKEND / name
    text = path.read_text()
    original = text
    text = ensure_import(
        text,
        "from auth import require_access\n",
        ("from fastapi import", "from openai import", "from pydantic import"),
    )
    text = replace_function(
        text,
        "_require_admin",
        "def _require_admin(token: str | None) -> None:\n    require_access(token)",
    )
    if text != original:
        path.write_text(text)
        return True
    return False


if __name__ == "__main__":
    changed = {
        "main.py": patch_main(),
        "team.py": patch_module("team.py"),
        "knowledge.py": patch_module("knowledge.py"),
        "crawler.py": patch_module("crawler.py"),
    }
    print(changed)
