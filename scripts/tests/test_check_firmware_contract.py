from __future__ import annotations

import configparser
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CHECKER = REPO_ROOT / "scripts" / "check_firmware_contract.py"
PLATFORMIO_INI = REPO_ROOT / "esp32_wifi" / "firmware" / "aero_hand_wifi" / "platformio.ini"
STANDALONE_FIRMWARE = (
    REPO_ROOT / "esp32_wifi" / "firmware" / "aero_hand_wifi" / "aero_hand_wifi.ino"
)
V020_FIRMWARE = REPO_ROOT / "firmware_ws" / "v0.2.0" / "firmware.ino"
V020_FIRMWARE_MIRROR = REPO_ROOT / "firmware_ws" / "v0.2.0" / "firmware" / "firmware.ino"
REQUIRED_PROTOCOL_MARKERS = (
    "hand_info",
    "firmware_type",
    "firmware_version",
    "protocol_version",
    "command_type",
    "request_id",
    "joint_control",
    "multi_joint_control",
    "actuator_control",
    "get_states",
    "homing",
    "wifi_status",
    "wifi_config_set",
    "wifi_connect_sta",
    "wifi_start_ap",
    "wifi_clear_sta",
)


def source_with_markers(markers: tuple[str, ...] = REQUIRED_PROTOCOL_MARKERS) -> str:
    declarations = [
        f'const char* marker_{index} = "{marker}";'
        for index, marker in enumerate(markers)
    ]
    return "\n".join(declarations) + "\n"


def run_checker(*targets: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(CHECKER), *(str(target) for target in targets)],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


class FirmwareContractCliTest(unittest.TestCase):
    def test_platformio_project_defines_cloud_build_environment(self) -> None:
        config = configparser.ConfigParser(interpolation=None)
        loaded = config.read(PLATFORMIO_INI, encoding="utf-8")

        self.assertEqual([str(PLATFORMIO_INI)], loaded)
        self.assertEqual("aero_hand_wifi", config["platformio"]["default_envs"])
        self.assertEqual(".", config["platformio"]["src_dir"])
        environment = config["env:aero_hand_wifi"]
        self.assertEqual("espressif32@6.10.0", environment["platform"])
        self.assertEqual("seeed_xiao_esp32s3", environment["board"])
        self.assertEqual("arduino", environment["framework"])
        self.assertEqual("partitions.csv", environment["board_build.partitions"])
        self.assertIn("bblanchon/ArduinoJson@7.3.1", environment["lib_deps"])
        self.assertIn("links2004/WebSockets@2.6.1", environment["lib_deps"])

    def test_accepts_compiled_binary_with_required_markers(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact = Path(temp_dir) / "firmware.bin"
            artifact.write_bytes(
                b"\xff\xfe\x00" + b"\x00".join(
                    marker.encode("ascii") for marker in REQUIRED_PROTOCOL_MARKERS
                )
            )

            result = run_checker(artifact)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_rejects_compiled_binary_missing_protocol_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact = Path(temp_dir) / "firmware.bin"
            artifact.write_bytes(
                b"\x00".join(marker.encode("ascii") for marker in REQUIRED_PROTOCOL_MARKERS[:-1])
            )

            result = run_checker(artifact)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("wifi_clear_sta", result.stdout)

    def test_default_target_is_standalone_firmware_source(self) -> None:
        result = run_checker()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_accepts_valid_source_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "firmware.ino"
            source.write_text(source_with_markers(), encoding="utf-8")

            result = run_checker(Path(temp_dir))

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_rejects_unterminated_cpp_string(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "firmware.cpp"
            source.write_text(
                'void logMessage() { Serial.printf("broken\n", 1); }\n',
                encoding="utf-8",
            )

            result = run_checker(source)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("unterminated string literal", result.stdout)

    def test_rejects_source_missing_protocol_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "firmware.ino"
            source.write_text(source_with_markers(REQUIRED_PROTOCOL_MARKERS[:-1]), encoding="utf-8")

            result = run_checker(source)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("missing required protocol markers", result.stdout)
        self.assertIn("wifi_clear_sta", result.stdout)

    def test_rejects_status_emitted_before_wifi_transition(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "firmware.ino"
            source.write_text(
                source_with_markers()
                + """
void processJsonCommand(const char* type) {
    if (strcmp(type, "wifi_start_ap") == 0) {
        sendResponse(true);
        sendWifiStatus();
        scheduleWifiAction(WIFI_ACTION_START_AP);
    }
}
""",
                encoding="utf-8",
            )

            result = run_checker(source)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("wifi_start_ap emits wifi_status before the transition", result.stdout)

    def test_rejects_config_handler_without_correlated_wifi_status(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "firmware.ino"
            source.write_text(
                source_with_markers()
                + """
void processJsonCommand(const char* type) {
    if (strcmp(type, "wifi_config_set") == 0) {
        sendResponse(true);
    }
}
""",
                encoding="utf-8",
            )

            result = run_checker(source)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("wifi_config_set does not emit correlated wifi_status", result.stdout)

    def test_repository_wifi_transition_sources_satisfy_contract(self) -> None:
        result = run_checker(STANDALONE_FIRMWARE, V020_FIRMWARE, V020_FIRMWARE_MIRROR)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(V020_FIRMWARE.read_bytes(), V020_FIRMWARE_MIRROR.read_bytes())


if __name__ == "__main__":
    unittest.main()
