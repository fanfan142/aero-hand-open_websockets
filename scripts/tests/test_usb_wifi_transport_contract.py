from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
FIRMWARE = REPO_ROOT / "firmware_ws" / "v0.2.0" / "firmware.ino"
FIRMWARE_MIRROR = REPO_ROOT / "firmware_ws" / "v0.2.0" / "firmware" / "firmware.ino"


def function_body(source: str, signature: str) -> str:
    search_from = 0
    while True:
        start = source.find(signature, search_from)
        if start < 0:
            break
        opening = source.find("{", start + len(signature))
        semicolon = source.find(";", start + len(signature))
        if opening < 0 or (semicolon >= 0 and semicolon < opening):
            search_from = start + len(signature)
            continue

        depth = 1
        for index in range(opening + 1, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    return source[opening + 1 : index]
        raise AssertionError(f"unterminated function: {signature}")
    raise AssertionError(f"function not found: {signature}")


def test_firmware_mirror_matches_release_source_byte_for_byte() -> None:
    assert FIRMWARE.read_bytes() == FIRMWARE_MIRROR.read_bytes()


def test_usb_ctrl_pos_updates_joint_cache_and_targets_without_changing_frame_decode() -> None:
    source = FIRMWARE.read_text(encoding="utf-8")
    handler = function_body(source, "static bool handleHostFrame(")
    ctrl_pos = handler.split("case CTRL_POS: {", 1)[1].split("case CTRL_TOR:", 1)[0]

    assert "payload[2 * i]" in ctrl_pos
    assert "payload[2 * i + 1]" in ctrl_pos
    assert "mapU16ToRaw((uint8_t)i, u16)" in ctrl_pos
    assert "updateJointAnglesFromActuatorAngles(g_actuatorAngles, g_jointAngles);" in ctrl_pos
    assert "copyCurrentJointAnglesToTargets();" in ctrl_pos
    assert ctrl_pos.index("updateJointAnglesFromActuatorAngles") < ctrl_pos.index("markControlActivity")


def test_ap_start_retries_are_bounded_and_reported_over_read_only_usb_query() -> None:
    source = FIRMWARE.read_text(encoding="utf-8")
    start_ap = function_body(source, "void startApMode()")
    attempt_ap = function_body(source, "static void attemptApStart()")
    retry_ap = function_body(source, "static void processApStartRetry()")
    diagnostics = function_body(source, "static void sendWifiDiagnostics()")
    host_frames = function_body(source, "static bool handleHostFrame(")
    loop = function_body(source, "void loop()")

    assert "attemptApStart();" in start_ap
    assert "g_apStartAttempts < AP_START_MAX_ATTEMPTS" in attempt_ap
    assert "WiFi.softAPConfig" in attempt_ap
    assert "WiFi.softAP(AP_SSID" in attempt_ap
    assert "AP_START_RETRY_INTERVAL_MS" in attempt_ap
    assert "g_apRetryPending" in retry_ap
    assert "case GET_WIFI_DIAG:" in host_frames
    assert "sendWifiDiagnostics();" in host_frames
    assert "WiFi.getMode()" in diagnostics
    assert "WiFi.softAPgetStationNum()" in diagnostics
    assert "WiFi.softAPIP()" in diagnostics
    assert "processApStartRetry();" in loop


def test_sta_association_is_loop_driven_with_deadline_and_ap_fallback() -> None:
    source = FIRMWARE.read_text(encoding="utf-8")
    connect = function_body(source, "void connectToSta(")
    association = function_body(source, "void processStaAssociation(")
    pending_action = function_body(source, "void processPendingWifiAction(")
    loop = function_body(source, "void loop(")

    assert "WiFi.begin(g_staSsid.c_str(), g_staPassword.c_str());" in connect
    assert "STA_ASSOCIATION_CONNECTING" in connect
    assert "delay(" not in connect
    assert "while (" not in connect
    assert "WiFi.status() == WL_CONNECTED" in association
    assert "STA_ASSOCIATION_TIMEOUT_MS" in association
    assert "startApMode();" in association
    assert "connectToSta(true, true);" in pending_action
    assert "wsServer.loop();" in loop
    assert "processStaAssociation();" in loop
    assert loop.index("processStaAssociation();") < loop.index("Serial.available()")


def test_wifi_transitions_cancel_queued_websocket_targets_and_report_pending_association() -> None:
    source = FIRMWARE.read_text(encoding="utf-8")
    schedule = function_body(source, "void scheduleWifiAction(")
    connect = function_body(source, "void connectToSta(")
    start_ap = function_body(source, "void startApMode(")
    cancel_lease = function_body(source, "static void cancelWebSocketControlLease(")
    wifi_status = function_body(source, "void sendWifiStatus(")

    assert "cancelWebSocketControlLease();" in schedule
    assert "cancelWebSocketControlLease();" in connect
    assert "cancelWebSocketControlLease();" in start_ap
    assert "cancelPendingWsTarget();" in cancel_lease
    assert "g_wsControlOwner = -1;" in cancel_lease
    assert "g_staAssociationState == STA_ASSOCIATION_CONNECTING" in wifi_status


def test_websocket_motion_cannot_be_queued_or_applied_during_homing() -> None:
    source = FIRMWARE.read_text(encoding="utf-8")
    command_handler = function_body(source, "void processJsonCommand(")
    target_application = function_body(source, "static void applyPendingWsTarget()")

    for command in ("joint_control", "actuator_control", "multi_joint_control"):
        branch_start = command_handler.index(f'strcmp(type, "{command}") == 0')
        branch = command_handler[branch_start : branch_start + 300]
        assert "HOMING_isBusy()" in branch
        assert '"Homing in progress"' in branch
    assert "if (HOMING_isBusy()) return;" in target_application


def test_preferences_namespaces_are_opened_safely_and_closed() -> None:
    source = FIRMWARE.read_text(encoding="utf-8")
    manual_load = function_body(source, "static void loadManualExtendsFromNVS()")
    manual_save = function_body(source, "static void saveExtendsToNVS()")
    wifi_load = function_body(source, "void loadWiFiConfig()")
    wifi_save = function_body(source, "void saveWiFiConfig()")
    wifi_clear = function_body(source, "void clearWiFiConfig()")
    trim = function_body(source, "static bool handleTrimCmd(")
    setup = function_body(source, "void setup()")

    assert 'if (!prefs.begin("hand", false)) return;' in manual_load
    assert 'if (!prefs.isKey(key.c_str())) continue;' in manual_load
    assert "prefs.end();" in manual_load
    assert 'if (!prefs.begin("hand", false)) return;' in manual_save
    assert "prefs.end();" in manual_save
    assert 'if (!wifiPrefs.begin("wifi", false)) return;' in wifi_load
    for key in ("mode", "sta_ssid", "sta_pass", "sta_ip", "sta_gw", "sta_mask", "sta_dns1", "sta_dns2"):
        assert f'wifiPrefs.isKey("{key}")' in wifi_load
        assert f'wifiPrefs.getString("{key}", ' not in wifi_load
    assert "wifiPrefs.end();" in wifi_load
    assert 'if (!wifiPrefs.begin("wifi", false)) return;' in wifi_save
    assert "wifiPrefs.end();" in wifi_save
    assert 'if (wifiPrefs.begin("wifi", false)) {' in wifi_clear
    assert "wifiPrefs.end();" in wifi_clear
    assert 'prefs.begin("hand", false);' not in setup
    assert 'if (prefs.begin("hand", false)) {' in trim
    assert "prefs.end();" in trim
