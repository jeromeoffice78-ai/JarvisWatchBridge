from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/app/src/main/java/com/jarvis/watchbridge/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Missing {label} anchor")
    return text.replace(old, new, 1)


def main() -> None:
    text = MAIN.read_text(encoding="utf-8")
    original = text

    text = replace_once(
        text,
        "import android.content.Intent\nimport android.os.Bundle\n",
        "import android.content.Intent\nimport android.os.Build\nimport android.os.Bundle\n",
        "Build import",
    )

    helper_anchor = "    private lateinit var moodEngine: MoodEngine\n\n"
    helper_block = """    private lateinit var moodEngine: MoodEngine

    private fun requiredBlePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

"""
    text = replace_once(text, helper_anchor, helper_block, "BLE permission helpers")

    old_launcher = """                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val denied = grants.filterValues { !it }.keys
                    roleMessage = if (denied.isEmpty()) \"Required permissions granted\" else \"Some capabilities remain permission-limited\"
                }

                val healthLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract()
                ) { }
"""
    new_launcher = """                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val denied = grants.filterValues { !it }.keys
                    roleMessage = if (denied.isEmpty()) {
                        \"Required permissions granted\"
                    } else {
                        \"Some capabilities remain permission-limited\"
                    }
                    val bleGranted = requiredBlePermissions().all { permission ->
                        grants[permission] == true || ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            permission
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (bleGranted && deviceRole == \"primary\") {
                        ble.connectTargetWatch()
                    }
                }

                val healthLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract()
                ) { }

                LaunchedEffect(deviceRole) {
                    if (deviceRole == \"primary\") {
                        val required = requiredBlePermissions()
                        val granted = required.all { permission ->
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                permission
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        if (granted) {
                            ble.connectTargetWatch()
                        } else {
                            permissionLauncher.launch(required)
                        }
                    }
                }
"""
    text = replace_once(text, old_launcher, new_launcher, "permission launcher")

    old_status = """                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatusTile(\"WATCH\", state.connectedName ?: \"Not connected\", Modifier.weight(1f))
                                StatusTile(\"ROLE\", deviceRole.uppercase(Locale.US), Modifier.weight(1f))
                            }
                        }
"""
    new_status = """                        item {
                            val watchStatus = when {
                                state.connectedName != null -> state.connectedName!!
                                state.connectingAddress != null -> \"Connecting…\"
                                state.scanning -> \"Scanning…\"
                                else -> \"Not connected\"
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatusTile(\"WATCH\", watchStatus, Modifier.weight(1f))
                                StatusTile(\"ROLE\", deviceRole.uppercase(Locale.US), Modifier.weight(1f))
                            }
                        }
"""
    text = replace_once(text, old_status, new_status, "watch status tile")

    if text != original:
        MAIN.write_text(text, encoding="utf-8")
        print("MainActivity.kt updated with role-aware watch auto-connect")
    else:
        print("MainActivity.kt already contains watch auto-connect integration")


if __name__ == "__main__":
    main()
