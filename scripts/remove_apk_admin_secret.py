import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "app"
SRC = ANDROID / "src" / "main" / "java"
BUILD = ANDROID / "build.gradle.kts"
MANIFEST = ANDROID / "src" / "main" / "AndroidManifest.xml"
ACTIVITY = SRC / "com" / "jarvis" / "watchbridge" / "MainActivity.kt"
AUTH_IMPORT = "import com.jarvis.watchbridge.auth.AuthStore\n"


def patch_kotlin_tokens() -> list[str]:
    changed: list[str] = []
    for path in SRC.rglob("*.kt"):
        text = path.read_text()
        if "BuildConfig.JARVIS_SETUP_TOKEN.trim()" not in text:
            continue
        original = text
        text = text.replace("BuildConfig.JARVIS_SETUP_TOKEN.trim()", "AuthStore.requireToken()")
        if AUTH_IMPORT not in text and "package com.jarvis.watchbridge.auth" not in text:
            package_end = text.find("\n", text.find("package "))
            if package_end < 0:
                raise RuntimeError(f"Package declaration not found in {path}")
            text = text[: package_end + 1] + "\n" + AUTH_IMPORT + text[package_end + 1 :]
        if text != original:
            path.write_text(text)
            changed.append(str(path.relative_to(ROOT)))
    return changed


def patch_build() -> bool:
    text = BUILD.read_text()
    original = text
    block = '''        val setupToken = System.getenv("JARVIS_SETUP_TOKEN")
            ?.takeIf { it.isNotBlank() }
            ?: ""
'''
    text = text.replace(block, "")
    text = re.sub(
        r'^\s*buildConfigField\("String", "JARVIS_SETUP_TOKEN", .*\)\s*\n',
        "",
        text,
        flags=re.MULTILINE,
    )
    if "JARVIS_SETUP_TOKEN" in text:
        raise RuntimeError("Android Gradle still references JARVIS_SETUP_TOKEN")
    if text != original:
        BUILD.write_text(text)
        return True
    return False


def patch_manifest() -> bool:
    text = MANIFEST.read_text()
    original = text
    if 'android:name=".JarvisApplication"' not in text:
        anchor = "    <application\n"
        if anchor not in text:
            raise RuntimeError("Android application manifest anchor not found")
        text = text.replace(anchor, anchor + '        android:name=".JarvisApplication"\n', 1)
    if text != original:
        MANIFEST.write_text(text)
        return True
    return False


def patch_activity() -> bool:
    text = ACTIVITY.read_text()
    original = text
    panel_import = "import com.jarvis.watchbridge.ui.ChairmanAccessPanel\n"
    if panel_import not in text:
        anchor = "import com.jarvis.watchbridge.ui.ExpertTeamPanel\n"
        if anchor not in text:
            raise RuntimeError("ExpertTeamPanel import anchor not found")
        text = text.replace(anchor, anchor + panel_import, 1)
    if "ChairmanAccessPanel()" not in text:
        anchor = "                        item {\n                            ExpertTeamPanel()\n                        }\n"
        if anchor not in text:
            raise RuntimeError("ExpertTeamPanel UI anchor not found")
        text = text.replace(
            anchor,
            "                        item {\n                            ChairmanAccessPanel()\n                        }\n\n" + anchor,
            1,
        )
    if text != original:
        ACTIVITY.write_text(text)
        return True
    return False


if __name__ == "__main__":
    print({
        "kotlin": patch_kotlin_tokens(),
        "build": patch_build(),
        "manifest": patch_manifest(),
        "activity": patch_activity(),
    })
