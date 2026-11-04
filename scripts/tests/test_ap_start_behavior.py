from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
FIRMWARE = REPO_ROOT / "firmware_ws" / "v0.2.0" / "firmware.ino"


def function_body(source: str, signature: str) -> str:
    """Return the body for a function definition, skipping prototypes."""
    search_from = 0
    while True:
        start = source.find(signature, search_from)
        if start < 0:
            raise AssertionError(f"function not found: {signature}")

        opening = source.find("{", start + len(signature))
        semicolon = source.find(";", start + len(signature))
        if opening < 0:
            raise AssertionError(f"function definition not found: {signature}")
        if semicolon >= 0 and semicolon < opening:
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


def harness_source(
    attempt_body: str, retry_body: str, start_body: str, diagnostics_body: str
) -> str:
    prefix = r"""
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

using String = std::string;
static constexpr int WIFI_AP = 2;
static constexpr int WIFI_STA = 1;
static constexpr uint8_t AP_START_MAX_ATTEMPTS = 3;
static constexpr uint32_t AP_START_RETRY_INTERVAL_MS = 1000;
static constexpr uint8_t GET_WIFI_DIAG = 0x26;
static constexpr const char* AP_SSID = "AeroHand_Right";
static constexpr const char* AP_PASSWORD = "12345678";
static constexpr uint8_t AP_CHANNEL = 6;
static constexpr uint8_t WS_MAX_CLIENTS = 4;
static constexpr int WL_CONNECTED = 3;

enum StaAssociationState { STA_ASSOCIATION_IDLE, STA_ASSOCIATION_CONNECTING };
static StaAssociationState g_staAssociationState = STA_ASSOCIATION_IDLE;
static bool g_staFallbackToAp = false;
static bool g_staSetDualOnSuccess = false;
static bool g_apConfigOk = false;
static bool g_apStarted = false;
static bool g_apRetryPending = false;
static uint8_t g_apStartAttempts = 0;
static uint32_t g_apRetryAt = 0;
static uint32_t now_ms = 0;
static bool config_result = true;
static bool start_result = false;
static int config_calls = 0;
static int start_calls = 0;
static int disconnect_calls = 0;
static uint8_t sent_frame[16] = {};

uint32_t millis() { return now_ms; }

struct IPAddress {
  uint8_t bytes[4] = {192, 168, 4, 1};
  IPAddress() = default;
  IPAddress(uint8_t, uint8_t, uint8_t, uint8_t) {}
  uint8_t operator[](size_t index) const { return bytes[index]; }
  String toString() const { return "192.168.4.1"; }
};

struct FakeWiFi {
  int mode_value = WIFI_AP;
  int status_value = 0;
  int stations = 0;
  void persistent(bool) {}
  void softAPdisconnect(bool) { ++disconnect_calls; }
  void disconnect(bool, bool) {}
  void mode(int value) { mode_value = value; }
  void setSleep(bool) {}
  bool softAPConfig(const IPAddress&, const IPAddress&, const IPAddress&) {
    ++config_calls;
    return config_result;
  }
  bool softAP(const char*, const char*, uint8_t, uint8_t, uint8_t) {
    ++start_calls;
    return start_result;
  }
  IPAddress softAPIP() const { return {}; }
  int getMode() const { return mode_value; }
  int softAPgetStationNum() const { return stations; }
  int status() const { return status_value; }
};
static FakeWiFi WiFi;

void sendAckFrame(uint8_t header, const uint8_t* payload, size_t length) {
  std::memset(sent_frame, 0, sizeof(sent_frame));
  sent_frame[0] = header;
  if (payload && length) std::memcpy(sent_frame + 2, payload, length > 14 ? 14 : length);
}

void cancelWebSocketControlLease() {}
void stopStaMdns() {}
void saveWiFiConfig() {}
void updateStaMdns(bool) {}

#define DEBUG_PRINTF(...) do {} while (0)
#define DEBUG_PRINT(...) do {} while (0)
#define DEBUG_PRINTLN(...) do {} while (0)

"""
    suffix = r"""

static void require_condition(bool condition, const char* message) {
  if (!condition) {
    std::fprintf(stderr, "behavior assertion failed: %s\n", message);
    std::exit(1);
  }
}

static void reset_state() {
  g_staAssociationState = STA_ASSOCIATION_IDLE;
  g_staFallbackToAp = false;
  g_staSetDualOnSuccess = false;
  g_apConfigOk = false;
  g_apStarted = false;
  g_apRetryPending = false;
  g_apStartAttempts = 0;
  g_apRetryAt = 0;
  now_ms = 0;
  config_result = true;
  start_result = false;
  config_calls = 0;
  start_calls = 0;
  disconnect_calls = 0;
  WiFi.mode_value = WIFI_AP;
  WiFi.status_value = 0;
  WiFi.stations = 0;
}

static void test_retry_schedule_and_success() {
  reset_state();
  now_ms = 100;
  start_result = false;
  startApMode();
  require_condition(g_apStartAttempts == 1, "AP startup must attempt immediately");
  require_condition(g_apRetryPending, "failed startup must schedule retry");
  require_condition(start_calls == 1, "startup must call softAP once");

  now_ms = 1099;
  processApStartRetry();
  require_condition(start_calls == 1, "retry must wait one second");
  now_ms = 1100;
  start_result = true;
  processApStartRetry();
  require_condition(start_calls == 2, "retry must call softAP again");
  require_condition(g_apStarted, "successful retry must update status");
  require_condition(!g_apRetryPending, "successful retry must stop retry schedule");
}

static void test_config_failure_is_reported_without_skipping_ap_start() {
  reset_state();
  config_result = false;
  start_result = true;
  startApMode();
  require_condition(!g_apConfigOk, "failed AP config must be reported");
  require_condition(g_apStarted, "AP start must still be attempted after config failure");
  require_condition(start_calls == 1, "softAP must be attempted once after config failure");
  require_condition(!g_apRetryPending, "successful softAP start must stop retries");
}

static void test_bounded_failure_and_timer_wrap() {
  reset_state();
  now_ms = 0xffffff00u;
  start_result = false;
  startApMode();
  uint32_t first_retry = g_apRetryAt;
  now_ms = static_cast<uint32_t>(first_retry - 1);
  processApStartRetry();
  require_condition(start_calls == 1, "retry deadline must handle uint32 wrap");
  now_ms = first_retry;
  processApStartRetry();
  uint32_t second_retry = g_apRetryAt;
  now_ms = second_retry;
  processApStartRetry();
  require_condition(g_apStartAttempts == AP_START_MAX_ATTEMPTS,
                    "AP startup retries must be bounded");
  require_condition(!g_apRetryPending, "exhausted retries must stop scheduling");
}

static void test_diagnostic_frame() {
  reset_state();
  g_apConfigOk = true;
  g_apStarted = false;
  g_apStartAttempts = 3;
  g_apRetryPending = false;
  WiFi.mode_value = WIFI_AP;
  WiFi.stations = 2;
  sendWifiDiagnostics();
  require_condition(sizeof(sent_frame) == 16, "diagnostics must use a 16-byte frame");
  require_condition(sent_frame[0] == GET_WIFI_DIAG && sent_frame[1] == 0,
                    "diagnostics must use the read-only query opcode");
  require_condition(sent_frame[2] == WIFI_AP, "mode must be reported");
  require_condition(sent_frame[3] == 1 && sent_frame[4] == 0,
                    "AP config and startup status must be distinct");
  require_condition(sent_frame[5] == 3 && sent_frame[7] == 2,
                    "attempt count and connected stations must be reported");
  require_condition(sent_frame[8] == 192 && sent_frame[11] == 1,
                    "AP address must be reported in network byte order");
}

int main() {
  test_retry_schedule_and_success();
  test_config_failure_is_reported_without_skipping_ap_start();
  test_bounded_failure_and_timer_wrap();
  test_diagnostic_frame();
  return 0;
}
"""
    return (
        prefix
        + "void attemptApStart() {" + attempt_body + "}\n\n"
        + "void processApStartRetry() {" + retry_body + "}\n\n"
        + "void startApMode() {" + start_body + "}\n\n"
        + "void sendWifiDiagnostics() {" + diagnostics_body + "}\n\n"
        + suffix
    )


class ApStartBehaviorTest(unittest.TestCase):
    @unittest.skipUnless(
        os.environ.get("GITHUB_ACTIONS", "").lower() == "true",
        "cloud-only behavioral regression test",
    )
    def test_production_ap_start_behavior_in_cpp_harness(self) -> None:
        source = FIRMWARE.read_text(encoding="utf-8")
        attempt_body = function_body(source, "static void attemptApStart()")
        retry_body = function_body(source, "static void processApStartRetry()")
        start_body = function_body(source, "void startApMode()")
        diagnostics_body = function_body(source, "static void sendWifiDiagnostics()")
        compiler = shutil.which("g++")
        if compiler is None:
            if os.name == "posix":
                self.fail("g++ is required for the cloud behavioral regression test")
            self.skipTest("g++ is unavailable on this non-Linux runner")

        with tempfile.TemporaryDirectory(prefix="ap-start-") as temp_dir:
            temp_path = Path(temp_dir)
            harness = temp_path / "ap_start_behavior.cpp"
            binary = temp_path / "ap_start_behavior"
            harness.write_text(
                harness_source(attempt_body, retry_body, start_body, diagnostics_body),
                encoding="utf-8",
            )
            compile_result = subprocess.run(
                [compiler, "-std=c++17", "-O0", str(harness), "-o", str(binary)],
                cwd=temp_path,
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            self.assertEqual(
                compile_result.returncode,
                0,
                compile_result.stdout + compile_result.stderr,
            )
            run_result = subprocess.run(
                [str(binary)],
                cwd=temp_path,
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            self.assertEqual(run_result.returncode, 0, run_result.stdout + run_result.stderr)
