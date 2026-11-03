from __future__ import annotations

import configparser
import shutil
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


def run_checker(*targets: Path, require_ble: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(CHECKER), *(["--require-ble"] if require_ble else []), *(str(target) for target in targets)],
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

    def test_ble_binary_gate_requires_every_uuid_only_when_requested(self) -> None:
        uuids = tuple(f"8c6f4a20-6c0b-4b7a-9d27-6df8a321000{index}" for index in range(1, 5))
        protocol = b"\x00".join(marker.encode("ascii") for marker in REQUIRED_PROTOCOL_MARKERS)
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact = Path(temp_dir) / "firmware.bin"
            artifact.write_bytes(protocol)
            standalone = run_checker(artifact)
            self.assertEqual(0, standalone.returncode, standalone.stdout + standalone.stderr)
            artifact.write_bytes(protocol + b"\x00" + b"\x00".join(uuid.encode("ascii") for uuid in uuids))
            accepted = run_checker(artifact, require_ble=True)
            self.assertEqual(0, accepted.returncode, accepted.stdout + accepted.stderr)
            for missing in uuids:
                with self.subTest(missing=missing):
                    artifact.write_bytes(protocol + b"\x00" + b"\x00".join(
                        uuid.encode("ascii") for uuid in uuids if uuid != missing
                    ))
                    rejected = run_checker(artifact, require_ble=True)
                    self.assertEqual(1, rejected.returncode, rejected.stdout + rejected.stderr)
                    self.assertIn(missing, rejected.stdout)

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


def cpp_function_definition(source: str, signature: str) -> str:
    # Include the opening brace so a forward declaration cannot match.
    start = source.index(signature + " {")
    end = source.index("\n}", start) + len("\n}")
    return source[start:end]


class FirmwareWsBleSourceContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = V020_FIRMWARE.read_text(encoding="utf-8")
        self.host_handler = cpp_function_definition(
            self.source,
            "static bool handleHostFrame(uint8_t op, const uint8_t* payload, ControlSource source)",
        )
        self.ble_setup = cpp_function_definition(self.source, "static void setupBlePeripheral()")

    def test_function_extraction_ignores_forward_declarations(self) -> None:
        source = "static void example();\nconst char* password;\nstatic void example() {\n  return;\n}\n"
        definition = cpp_function_definition(source, "static void example()")
        self.assertEqual("static void example() {\n  return;\n}", definition)
        self.assertNotIn("password", definition)

    def test_ble_uuid_contract_and_pinned_dependency(self) -> None:
        expected_uuids = (
            "8c6f4a20-6c0b-4b7a-9d27-6df8a3210001",
            "8c6f4a20-6c0b-4b7a-9d27-6df8a3210002",
            "8c6f4a20-6c0b-4b7a-9d27-6df8a3210003",
            "8c6f4a20-6c0b-4b7a-9d27-6df8a3210004",
        )
        uuid_names = ("BLE_SERVICE_UUID", "BLE_COMMAND_UUID", "BLE_RESPONSE_UUID", "BLE_IDENTITY_UUID")
        for name, uuid in zip(uuid_names, expected_uuids):
            with self.subTest(name=name):
                self.assertIn(f'{name}[] = "{uuid}"', self.source)
        generator = (REPO_ROOT / "scripts" / "build_versions.py").read_text(encoding="utf-8")
        self.assertIn("h2zero/NimBLE-Arduino@2.3.6", generator)
        self.assertIn('"espressif32@6.10.0" if ble_enabled else "espressif32"', generator)
        self.assertIn("NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC", self.source)
        self.assertIn("NIMBLE_PROPERTY::NOTIFY", self.source)
        self.assertIn("NIMBLE_PROPERTY::READ", self.source)
        self.assertIn(r'\"capabilities\":[\"CTRL_POS\",\"GET_POS\",\"HOMING\"]', self.source)
        self.assertNotIn("password", self.ble_setup)
        self.assertNotIn("sta_pass", self.ble_setup)

    def test_ble_command_requires_encrypted_bonded_exact_frame(self) -> None:
        self.assertIn("NimBLEDevice::setSecurityAuth(true, false, true)", self.source)
        self.assertIn("Just Works", self.source)
        self.assertIn("!connInfo.isEncrypted() || !connInfo.isBonded() || value.size() != 16", self.source)
        self.assertIn("connInfo.getConnHandle() != g_bleActiveConnHandle", self.source)
        self.assertIn("g_hostResponseSource = source", self.source)
        response = cpp_function_definition(self.source, "static void sendHostPacket(const uint8_t* packet, size_t length)")
        dispatch = cpp_function_definition(self.source, "static void processBleQueue()")
        dequeue = dispatch.index("g_bleCommandQueue.takeNext(")
        handle_snapshot = dispatch.index("const uint16_t connHandle = g_bleActiveConnHandle", dequeue)
        unlock = dispatch.index("portEXIT_CRITICAL(&g_bleQueueMux)", handle_snapshot)
        self.assertLess(dequeue, handle_snapshot)
        self.assertLess(handle_snapshot, unlock)
        self.assertIn("g_bleDispatchConnHandle = connHandle", dispatch)
        self.assertIn("g_bleDispatchGeneration = generation", dispatch)
        self.assertIn("g_hostResponseConnHandle = (source == CONTROL_SOURCE_BLE) ? g_bleDispatchConnHandle : 0xFFFF", self.host_handler)
        self.assertIn("g_hostResponseGeneration = (source == CONTROL_SOURCE_BLE) ? g_bleDispatchGeneration : 0", self.host_handler)
        self.assertIn("const uint16_t connHandle = g_hostResponseConnHandle", response)
        self.assertIn("bleDispatchConnectionIsCurrent(generation, connHandle)", response)
        self.assertIn("g_bleResponseCharacteristic->notify(connHandle)", response)
        self.assertNotIn("g_bleActiveConnHandle", response)
        self.assertIn("Serial.write(packet, length)", response)

    def test_ble_callback_only_queues_allowed_commands(self) -> None:
        start = self.source.index("class AeroBleCommandCallbacks")
        end = self.source.index("\n};", start) + len("\n};")
        callback_section = self.source[start:end]
        self.assertIn("op != CTRL_POS && op != GET_POS && op != HOMING", callback_section)
        self.assertIn("g_bleCommandQueue.enqueue(frame, generation, sequence, arrivedAtMs)", callback_section)
        self.assertIn("const uint32_t arrivedAtMs = millis()", callback_section)
        for forbidden in ("hlscl.", "HOMING_start(", "handleHostFrame(", "SyncWritePosEx(", "WriteEle("):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, callback_section)

    def test_ble_queue_runs_from_main_loop_and_cancels_disconnected_generation(self) -> None:
        loop_start = self.source.index("void loop() {")
        loop_end = self.source.index("\n}", loop_start)
        self.assertIn("processBleQueue();", self.source[loop_start:loop_end])
        self.assertIn("handleHostFrame(op, &frame[2], CONTROL_SOURCE_BLE)", self.source)
        self.assertIn("g_bleCommandQueue.discardGeneration(disconnectedGeneration)", self.source)
        self.assertIn("g_bleConnectionGeneration == generation", self.source)

        server_start = self.source.index("class AeroBleServerCallbacks")
        server_end = self.source.index("\n};", server_start) + len("\n};")
        server_callbacks = self.source[server_start:server_end]
        connect_start = server_callbacks.index("void onConnect")
        connect_end = server_callbacks.index("void onDisconnect", connect_start)
        connect_callback = server_callbacks[connect_start:connect_end]
        self.assertIn("if (g_bleConnected)", connect_callback)
        self.assertIn("server->disconnect(connInfo)", connect_callback)
        self.assertIn("g_bleActiveConnHandle = connInfo.getConnHandle()", connect_callback)

        get_pos_start = self.host_handler.index("case GET_POS:")
        get_pos_end = self.host_handler.index("case GET_VEL:", get_pos_start)
        get_pos_body = self.host_handler[get_pos_start:get_pos_end]
        self.assertNotIn("g_lastBleActivity", get_pos_body)
        self.assertNotIn("markControlActivity(", get_pos_body)
        dispatch = cpp_function_definition(self.source, "static void processBleQueue()")
        self.assertNotIn("g_lastBleActivity", dispatch)
        self.assertNotIn("markControlActivity(", dispatch)

        constants_end = self.source.index("static const uint8_t SET_TOR")
        callbacks_start = self.source.index("class AeroBleCommandCallbacks")
        handler_prototype = self.source.index("static bool handleHostFrame(uint8_t op, const uint8_t* payload, ControlSource source);")
        self.assertLess(constants_end, callbacks_start)
        self.assertLess(handler_prototype, callbacks_start)

    def test_ble_queue_policy_coalesces_expires_discards_and_orders_wraparound(self) -> None:
        compiler = shutil.which("g++") or shutil.which("clang++")
        if compiler is None:
            self.fail("BLE queue behavior coverage requires g++ or clang++ (provided by Firmware CI)")

        start = self.source.index("// BLE_QUEUE_POLICY_BEGIN") + len("// BLE_QUEUE_POLICY_BEGIN")
        end = self.source.index("// BLE_QUEUE_POLICY_END", start)
        policy = self.source[start:end]
        harness = r"""
#include <stdint.h>
#include <string.h>
#include <assert.h>
static const uint8_t CTRL_POS = 0x11;
static const uint8_t GET_POS = 0x22;
static const uint8_t HOMING = 0x01;
""" + policy + r"""
static void makeFrame(uint8_t frame[16], uint8_t op, uint8_t fill) {
  memset(frame, fill, 16);
  frame[0] = op;
}
int main() {
  BleCommandQueue queue;
  uint8_t frame[16], out[16];
  uint32_t generation, sequence, arrivedAt;
  makeFrame(frame, CTRL_POS, 1);
  queue.enqueue(frame, 7, 0xFFFFFFFEU, 100);
  makeFrame(frame, CTRL_POS, 2);
  queue.enqueue(frame, 7, 0xFFFFFFFFU, 110);
  assert(queue.takeNext(120, 7, out, &generation, &sequence, &arrivedAt));
  assert(out[1] == 2 && generation == 7 && sequence == 0xFFFFFFFFU && arrivedAt == 110);

  makeFrame(frame, CTRL_POS, 3);
  queue.enqueue(frame, 7, 0xFFFFFFFFU, 200);
  makeFrame(frame, GET_POS, 4);
  queue.enqueue(frame, 7, 0, 201);
  assert(queue.takeNext(202, 7, out, &generation, &sequence, &arrivedAt));
  assert(out[0] == CTRL_POS && sequence == 0xFFFFFFFFU);
  assert(queue.takeNext(202, 7, out, &generation, &sequence, &arrivedAt));
  assert(out[0] == GET_POS && sequence == 0);

  // The safe command must also win when it precedes the latest position across wrap.
  makeFrame(frame, GET_POS, 1);
  queue.enqueue(frame, 7, 0xFFFFFFFFU, 210);
  makeFrame(frame, CTRL_POS, 2);
  queue.enqueue(frame, 7, 0, 211);
  assert(queue.takeNext(212, 7, out, &generation, &sequence, &arrivedAt));
  assert(out[0] == GET_POS && sequence == 0xFFFFFFFFU);
  assert(queue.takeNext(212, 7, out, &generation, &sequence, &arrivedAt));
  assert(out[0] == CTRL_POS && sequence == 0);

  // Polling entries coalesce too, without reordering an earlier homing command.
  makeFrame(frame, GET_POS, 1);
  queue.enqueue(frame, 7, 1, 220);
  makeFrame(frame, HOMING, 2);
  queue.enqueue(frame, 7, 2, 221);
  makeFrame(frame, GET_POS, 3);
  queue.enqueue(frame, 7, 3, 222);
  assert(queue.takeNext(223, 7, out, &generation, &sequence, &arrivedAt));
  assert(out[0] == HOMING);
  assert(queue.takeNext(223, 7, out, &generation, &sequence, &arrivedAt));
  assert(out[0] == GET_POS && out[1] == 3 && arrivedAt == 222);
  assert(!queue.takeNext(223, 7, out, &generation, &sequence, &arrivedAt));

  makeFrame(frame, CTRL_POS, 5);
  queue.enqueue(frame, 7, 2, 300);
  assert(!queue.takeNext(600, 7, out, &generation, &sequence, &arrivedAt));
  makeFrame(frame, GET_POS, 6);
  queue.enqueue(frame, 7, 3, 700);
  assert(queue.takeNext(1699, 7, out, &generation, &sequence, &arrivedAt));
  assert(out[0] == GET_POS && arrivedAt == 700);
  makeFrame(frame, GET_POS, 7);
  queue.enqueue(frame, 7, 4, 2000);
  assert(!queue.takeNext(3000, 7, out, &generation, &sequence, &arrivedAt));
  makeFrame(frame, HOMING, 8);
  queue.enqueue(frame, 7, 5, 3700);
  assert(!queue.takeNext(6700, 7, out, &generation, &sequence, &arrivedAt));

  makeFrame(frame, CTRL_POS, 9);
  queue.enqueue(frame, 7, 6, 0xFFFFFFF0U);
  assert(!queue.takeNext(0x0000011CU, 7, out, &generation, &sequence, &arrivedAt));

  makeFrame(frame, CTRL_POS, 7);
  queue.enqueue(frame, 7, 4, 4000);
  makeFrame(frame, GET_POS, 8);
  queue.enqueue(frame, 7, 5, 4000);
  makeFrame(frame, HOMING, 9);
  queue.enqueue(frame, 7, 6, 4000);
  queue.discardGeneration(7);
  assert(!queue.takeNext(4001, 7, out, &generation, &sequence, &arrivedAt));

  // Reconnection never dispatches an old generation, even without an explicit purge.
  makeFrame(frame, CTRL_POS, 1);
  queue.enqueue(frame, 7, 7, 7000);
  makeFrame(frame, GET_POS, 2);
  queue.enqueue(frame, 7, 8, 7000);
  makeFrame(frame, HOMING, 3);
  queue.enqueue(frame, 7, 9, 7000);
  assert(!queue.takeNext(7001, 8, out, &generation, &sequence, &arrivedAt));

  // A delayed disconnect purge leaves the new central's frames intact.
  makeFrame(frame, CTRL_POS, 4);
  queue.enqueue(frame, 7, 10, 7100);
  makeFrame(frame, GET_POS, 5);
  queue.enqueue(frame, 8, 11, 7100);
  makeFrame(frame, HOMING, 6);
  queue.enqueue(frame, 7, 12, 7100);
  queue.discardGeneration(7);
  assert(queue.takeNext(7101, 8, out, &generation, &sequence, &arrivedAt));
  assert(out[0] == GET_POS && generation == 8 && out[1] == 5);
  assert(!queue.takeNext(7101, 8, out, &generation, &sequence, &arrivedAt));
  return 0;
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "ble_queue_contract.cpp"
            executable = Path(temp_dir) / ("ble_queue_contract.exe" if sys.platform == "win32" else "ble_queue_contract")
            source.write_text(harness, encoding="utf-8")
            build = subprocess.run(
                [compiler, "-std=c++11", str(source), "-o", str(executable)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, build.returncode, build.stdout + build.stderr)
            result = subprocess.run([str(executable)], capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_ble_control_changes_revalidate_after_bus_wait_before_cache_commit(self) -> None:
        ctrl_start = self.host_handler.index("case CTRL_POS:")
        ctrl_end = self.host_handler.index("case CTRL_TOR:", ctrl_start)
        ctrl_body = self.host_handler[ctrl_start:ctrl_end]
        bus_wait = ctrl_body.index("xSemaphoreTake(gBusMux, portMAX_DELAY)")
        freshness_check = ctrl_body.index("bleDispatchConnectionIsCurrent(g_bleDispatchGeneration, g_bleDispatchConnHandle)", bus_wait)
        servo_write = ctrl_body.index("hlscl.SyncWritePosEx", freshness_check)
        cache_commit = ctrl_body.index("memcpy(g_actuatorAngles", servo_write)
        self.assertLess(bus_wait, freshness_check)
        self.assertLess(freshness_check, servo_write)
        self.assertLess(servo_write, cache_commit)
        self.assertIn("millis() - g_bleDispatchArrivedAtMs", ctrl_body)
        self.assertNotIn("memcpy(g_actuatorAngles", ctrl_body[:freshness_check])
        self.assertNotIn("cancelPendingWsTarget()", ctrl_body[:freshness_check])
        self.assertIn("g_bleActiveConnHandle == g_bleDispatchConnHandle", ctrl_body)
        self.assertIn("if (bleSessionStillCurrent) {", ctrl_body)

    def test_ble_critical_sections_never_cover_io_or_motion(self) -> None:
        marker = "portENTER_CRITICAL(&g_bleQueueMux);"
        for index, section in enumerate(self.source.split(marker)[1:]):
            # Include early-return unlock paths and the normal-path final unlock.
            final_unlock = section.rindex("portEXIT_CRITICAL(&g_bleQueueMux);")
            critical_section = section[:final_unlock]
            for forbidden in ("hlscl.", "HOMING_start(", "handleHostFrame(",
                              "xSemaphoreTake(", "->notify(", "->setValue(", "server->disconnect("):
                with self.subTest(section=index, forbidden=forbidden):
                    self.assertNotIn(forbidden, critical_section)

    def test_homing_guard_rechecks_after_bus_wait_before_any_side_effect(self) -> None:
        compiler = shutil.which("g++") or shutil.which("clang++")
        if compiler is None:
            self.fail("Guarded homing behavior coverage requires g++ or clang++")
        homing_source = (V020_FIRMWARE.parent / "Homing.cpp").read_text(encoding="utf-8")
        homing_header = (V020_FIRMWARE.parent / "Homing.h").read_text(encoding="utf-8")
        context_start = homing_header.index("struct HomingGuardContext {")
        context_end = homing_header.index("// Utility", context_start)
        declarations = homing_header[context_start:context_end]
        locked = cpp_function_definition(homing_source, "static void zero_all_motors_locked()")
        guarded = cpp_function_definition(homing_source, "bool HOMING_startGuarded(HomingStartGuard guard, const HomingGuardContext& context)")
        legacy = cpp_function_definition(homing_source, "void zero_all_motors()") + "\n" + cpp_function_definition(homing_source, "void HOMING_start()")
        guard = cpp_function_definition(self.source, "static bool bleHomingStartAllowed(const HomingGuardContext& context)")
        case_start = self.host_handler.index("case HOMING:")
        case_end = self.host_handler.index("case SET_ID:", case_start)
        homing_case = self.host_handler[case_start:case_end]
        harness = r"""
#include <stdint.h>
#include <assert.h>
""" + declarations + r"""
static bool s_busy = false, busOwned = false, connected = true;
static int scenario, takes, gives, baselineResets, motorCalls, postHoming, nvs, cancels, acks, marks;
static uint32_t nowMs;
static uint32_t g_bleDispatchGeneration = 7, g_bleDispatchArrivedAtMs = 100;
static uint16_t g_bleDispatchConnHandle = 42;
static const uint32_t BLE_HOMING_TTL_MS = 3000;
static const uint8_t HOMING = 0x01;
static const int JOINT_COUNT = 2, SERVO_COUNT = 7, pdTRUE = 1, portMAX_DELAY = -1;
static int gBusMux = 1;
static float g_jointAngles[JOINT_COUNT], g_targetJointAngles[JOINT_COUNT];
static float g_actuatorAngles[SERVO_COUNT], g_targetActuatorAngles[SERVO_COUNT];
struct ServoData { int servo_direction, extend_count; };
static ServoData sd[7];
static const uint8_t SERVO_IDS[7] = {0, 1, 2, 3, 4, 5, 6};
enum ControlSource { CONTROL_SOURCE_SERIAL, CONTROL_SOURCE_BLE };
static uint32_t millis() { return nowMs; }
static int xSemaphoreTake(int, int) {
  assert(!busOwned); // Detect recursive acquisition.
  busOwned = true;
  ++takes;
  if (scenario == 1) connected = false; // Disconnect while waiting for the bus.
  if (scenario == 2) nowMs = 3100; // Expire exactly as the bus becomes available.
  return pdTRUE;
}
static void xSemaphoreGive(int) { assert(busOwned); busOwned = false; ++gives; }
static bool bleDispatchConnectionIsCurrent(uint32_t generation, uint16_t handle) {
  assert(busOwned); // The production guard must run after acquisition.
  return connected && generation == 7 && handle == 42;
}
static bool canAcceptControl(ControlSource) { return true; }
static void resetSdToBaseline() { assert(busOwned); ++baselineResets; sd[0].extend_count = 2048; }
static void zero_with_current(uint8_t, int, int) { assert(busOwned); ++motorCalls; }
static void zero_fingers_parallel_with_current(uint8_t, uint8_t, int) { assert(busOwned); ++motorCalls; }
struct MotorBus {
  void WritePosEx(uint8_t, int, int, int, int) { assert(busOwned); ++motorCalls; }
} hlscl;
static void enforceServoCurrentLimitPostHoming() { ++postHoming; }
static void cancelPendingWsTarget() { ++cancels; }
static void saveExtendsToNVS() { ++nvs; }
static void sendAckFrame(uint8_t, const uint8_t*, unsigned) { ++acks; }
static void markControlActivity(ControlSource) { ++marks; }
""" + locked + "\n" + legacy + "\n" + guarded + "\n" + guard + r"""
static bool dispatchHoming() {
  const ControlSource source = CONTROL_SOURCE_BLE;
  switch (HOMING) {
""" + homing_case + r"""
    default: return false;
  }
}
int main() {
  for (scenario = 0; scenario < 3; ++scenario) {
    connected = true;
    nowMs = 101;
    takes = gives = baselineResets = motorCalls = postHoming = nvs = cancels = acks = marks = 0;
    sd[0].extend_count = 1234;
    for (int i = 0; i < JOINT_COUNT; ++i) g_jointAngles[i] = g_targetJointAngles[i] = 9;
    for (int i = 0; i < SERVO_COUNT; ++i) g_actuatorAngles[i] = g_targetActuatorAngles[i] = 9;
    assert(dispatchHoming());
    assert(takes == 1 && gives == 1 && !busOwned && !s_busy);
    if (scenario == 0) {
      assert(baselineResets == 1 && motorCalls == 11 && postHoming == 1);
      assert(nvs == 1 && cancels == 1 && acks == 1 && marks == 1);
      assert(sd[0].extend_count == 2048);
    } else {
      assert(baselineResets == 0 && motorCalls == 0 && postHoming == 0);
      assert(nvs == 0 && cancels == 0 && acks == 0 && marks == 0);
      assert(sd[0].extend_count == 1234);
    }
    const float expected = scenario == 0 ? 0 : 9;
    for (int i = 0; i < JOINT_COUNT; ++i) assert(g_jointAngles[i] == expected && g_targetJointAngles[i] == expected);
    for (int i = 0; i < SERVO_COUNT; ++i) assert(g_actuatorAngles[i] == expected && g_targetActuatorAngles[i] == expected);
  }
  return 0;
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "homing_guard_contract.cpp"
            executable = Path(temp_dir) / ("homing_guard_contract.exe" if sys.platform == "win32" else "homing_guard_contract")
            source.write_text(harness, encoding="utf-8")
            build = subprocess.run([compiler, "-std=c++11", str(source), "-o", str(executable)], capture_output=True, text=True, check=False)
            self.assertEqual(0, build.returncode, build.stdout + build.stderr)
            result = subprocess.run([str(executable)], capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_ble_mirror_is_exact(self) -> None:
        self.assertEqual(V020_FIRMWARE.read_bytes(), V020_FIRMWARE_MIRROR.read_bytes())


if __name__ == "__main__":
    unittest.main()
