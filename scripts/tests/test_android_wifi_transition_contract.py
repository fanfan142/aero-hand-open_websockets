from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
HAND_CONTROL_VIEW_MODEL = (
    REPO_ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "aerohand"
    / "viewmodel"
    / "HandControlViewModel.kt"
)


def test_clear_sta_success_targets_ap_endpoint() -> None:
    source = HAND_CONTROL_VIEW_MODEL.read_text(encoding="utf-8")
    clear_sta_handler = source.split("fun clearStaConfig()", 1)[1].split("fun runPreset(", 1)[0]
    success_handler = clear_sta_handler.split("DeviceCommandResult.Success ->", 1)[1].split(
        "is DeviceCommandResult.Failure",
        1,
    )[0]

    assert 'host = "192.168.4.1"' in success_handler
    assert 'port = "8765"' in success_handler


def test_wifi_status_does_not_overwrite_connection_target() -> None:
    source = HAND_CONTROL_VIEW_MODEL.read_text(encoding="utf-8")
    status_collector = source.split(
        "webSocketService.wifiStatus.collectLatest",
        1,
    )[1].split("webSocketService.capabilities.collectLatest", 1)[0]

    assert "host =" not in status_collector
