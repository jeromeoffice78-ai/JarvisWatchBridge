from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
backend = ROOT / "backend" / "main.py"
activity = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "jarvis" / "watchbridge" / "MainActivity.kt"


def patch_backend() -> bool:
    text = backend.read_text()
    changed = False
    import_line = "from team import router as team_router\n"
    if import_line not in text:
        anchor = "from openai import OpenAI\n"
        if anchor not in text:
            raise RuntimeError("backend OpenAI import anchor not found")
        text = text.replace(anchor, anchor + import_line, 1)
        changed = True

    include_line = "app.include_router(team_router)\n"
    if include_line not in text:
        anchor = 'app = FastAPI(title="JARVIS Watch Bridge API", version="0.6.0")\n'
        if anchor not in text:
            raise RuntimeError("FastAPI app anchor not found")
        text = text.replace(anchor, anchor + include_line, 1)
        changed = True

    if changed:
        backend.write_text(text)
    return changed


def patch_android() -> bool:
    text = activity.read_text()
    changed = False
    import_line = "import com.jarvis.watchbridge.ui.ExpertTeamPanel\n"
    if import_line not in text:
        anchor = "import com.jarvis.watchbridge.ui.JarvisVisualState\n"
        if anchor not in text:
            raise RuntimeError("MainActivity JarvisVisualState import anchor not found")
        text = text.replace(anchor, anchor + import_line, 1)
        changed = True

    if "ExpertTeamPanel()" not in text:
        role_tile = 'StatusTile("ROLE", deviceRole.uppercase(Locale.US), Modifier.weight(1f))'
        pos = text.find(role_tile)
        if pos < 0:
            raise RuntimeError("MainActivity role tile anchor not found")
        next_item = text.find("\n\n                        item {", pos)
        if next_item < 0:
            raise RuntimeError("MainActivity next item anchor not found")
        insertion = "\n\n                        item {\n                            ExpertTeamPanel()\n                        }"
        text = text[:next_item] + insertion + text[next_item:]
        changed = True

    if changed:
        activity.write_text(text)
    return changed


if __name__ == "__main__":
    backend_changed = patch_backend()
    android_changed = patch_android()
    print(f"backend_changed={backend_changed} android_changed={android_changed}")
