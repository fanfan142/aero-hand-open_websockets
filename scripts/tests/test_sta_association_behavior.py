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
    """Return the body for a definition, skipping prototypes before it."""
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


def harness_source(connect_body: str, association_body: str) -> str:
    prefix = r"""
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>

using String = std::string;

static constexpr int WIFI_STA = 1;
static constexpr int WIFI_AP = 2;
static constexpr int WL_DISCONNECTED = 0;
static constexpr int WL_CONNECTED = 3;
static constexpr uint8_t AH_WIFI_MODE_DUAL = 3;
static constexpr uint32_t STA_ASSOCIATION_TIMEOUT_MS = 15000;

enum StaAssociationState {
  STA_ASSOCIATION_IDLE = 0,
  STA_ASSOCIATION_CONNECTING = 1,
};

static StaAssociationState g_staAssociationState = STA_ASSOCIATION_IDLE;
static uint32_t g_staConnectionStartedAt = 0;
static bool g_staFallbackToAp = false;
static bool g_staSetDualOnSuccess = false;
static uint8_t g_wifiModeSetting = 0;
static String g_staSsid = "ssid";
static String g_staPassword = "password";
static String g_staStaticIp = "192.168.1.20";
static String g_staGateway = "192.168.1.1";
static String g_staSubnet = "255.255.255.0";
static String g_staDns1 = "8.8.8.8";
static String g_staDns2 = "1.1.1.1";

static uint32_t now_ms = 0;
static bool config_valid = true;
static bool static_config_ok = true;
static int cancel_lease_calls = 0;
static int stop_mdns_calls = 0;
static int fallback_calls = 0;
static int save_config_calls = 0;
static int mdns_update_calls = 0;

uint32_t millis() {
  return now_ms;
}

struct FakeWiFi {
  int status_value = WL_DISCONNECTED;
  int begin_calls = 0;
  int mode_value = 0;

  int status() const { return status_value; }
  void persistent(bool) {}
  void softAPdisconnect(bool) {}
  void disconnect(bool, bool) {}
  void mode(int value) { mode_value = value; }
  void setSleep(bool) {}
  void begin(const char*, const char*) { ++begin_calls; }
  int localIP() const { return 0; }
};

static FakeWiFi WiFi;

#define DEBUG_PRINTF(...) do {} while (0)
#define DEBUG_PRINT(...) do {} while (0)
#define DEBUG_PRINTLN(...) do {} while (0)

const char* validateStaConfig(
    const String&, const String&, const String&, const String&,
    const String&, const String&, const String&) {
  return config_valid ? nullptr : "invalid test config";
}

void cancelWebSocketControlLease() {
  ++cancel_lease_calls;
}

void stopStaMdns() {
  ++stop_mdns_calls;
}

bool applyStaStaticConfig() {
  return static_config_ok;
}

void startApMode() {
  ++fallback_calls;
  g_staAssociationState = STA_ASSOCIATION_IDLE;
  g_staFallbackToAp = false;
  g_staSetDualOnSuccess = false;
}

void saveWiFiConfig() {
  ++save_config_calls;
}

void updateStaMdns(bool) {
  ++mdns_update_calls;
}

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
  g_staConnectionStartedAt = 0;
  g_staFallbackToAp = false;
  g_staSetDualOnSuccess = false;
  g_wifiModeSetting = 0;
  now_ms = 0;
  config_valid = true;
  static_config_ok = true;
  cancel_lease_calls = 0;
  stop_mdns_calls = 0;
  fallback_calls = 0;
  save_config_calls = 0;
  mdns_update_calls = 0;
  WiFi.status_value = WL_DISCONNECTED;
  WiFi.begin_calls = 0;
  WiFi.mode_value = 0;
}

static void test_waiting_and_deadline() {
  reset_state();
  now_ms = 1000;
  connectToSta(true, true);
  require_condition(g_staAssociationState == STA_ASSOCIATION_CONNECTING,
                    "connectToSta must return while association is pending");
  require_condition(WiFi.begin_calls == 1, "STA association must start once");
  require_condition(fallback_calls == 0, "pending association must not fall back");

  now_ms = 15999;
  processStaAssociation();
  require_condition(g_staAssociationState == STA_ASSOCIATION_CONNECTING,
                    "14999ms must remain within the association deadline");
  require_condition(fallback_calls == 0, "14999ms must not fall back");

  now_ms = 16000;
  processStaAssociation();
  require_condition(fallback_calls == 1, "15000ms must fall back to AP");
  require_condition(g_staAssociationState == STA_ASSOCIATION_IDLE,
                    "timeout must leave association idle");
}

static void test_success_and_idle_no_repeat() {
  reset_state();
  now_ms = 200;
  connectToSta(false, true);
  WiFi.status_value = WL_CONNECTED;
  now_ms = 201;
  processStaAssociation();
  require_condition(g_staAssociationState == STA_ASSOCIATION_IDLE,
                    "success must leave association idle");
  require_condition(g_wifiModeSetting == AH_WIFI_MODE_DUAL,
                    "successful dual-mode association must set DUAL");
  require_condition(save_config_calls == 1,
                    "successful dual-mode association must save once");
  require_condition(mdns_update_calls == 1,
                    "successful association must update mDNS once");

  processStaAssociation();
  require_condition(save_config_calls == 1, "idle processing must not save again");
  require_condition(mdns_update_calls == 1, "idle processing must not update mDNS again");
}

static void test_deadline_wrap() {
  reset_state();
  now_ms = 0xffffff00u;
  connectToSta(true, false);
  require_condition(g_staAssociationState == STA_ASSOCIATION_CONNECTING,
                    "wrap test must start association");

  now_ms = static_cast<uint32_t>(g_staConnectionStartedAt + 14999u);
  processStaAssociation();
  require_condition(fallback_calls == 0, "14999ms across uint32 wrap must not fall back");

  now_ms = static_cast<uint32_t>(g_staConnectionStartedAt + 15000u);
  processStaAssociation();
  require_condition(fallback_calls == 1, "15000ms across uint32 wrap must fall back");
}

static void test_invalid_config_and_config_failure_fallback() {
  reset_state();
  config_valid = false;
  connectToSta(true, true);
  require_condition(WiFi.begin_calls == 0, "invalid config must not start WiFi");
  require_condition(fallback_calls == 1, "invalid config must fall back immediately");
  require_condition(g_staAssociationState == STA_ASSOCIATION_IDLE,
                    "invalid config fallback must remain idle");

  reset_state();
  static_config_ok = false;
  connectToSta(true, true);
  require_condition(WiFi.begin_calls == 0, "config failure must not start association");
  require_condition(fallback_calls == 1, "config failure must fall back immediately");
  require_condition(g_staAssociationState == STA_ASSOCIATION_IDLE,
                    "config failure fallback must remain idle");
}

int main() {
  test_waiting_and_deadline();
  test_success_and_idle_no_repeat();
  test_deadline_wrap();
  test_invalid_config_and_config_failure_fallback();
  return 0;
}
"""
    return prefix + "void connectToSta(bool fallbackToAp, bool setDualOnSuccess) {" + connect_body + "}\n\n" + "void processStaAssociation() {" + association_body + "}\n" + suffix


class StaAssociationBehaviorTest(unittest.TestCase):
    @unittest.skipUnless(
        os.environ.get("GITHUB_ACTIONS", "").lower() == "true",
        "cloud-only behavioral regression test",
    )
    def test_production_sta_association_behavior_in_cpp_harness(self) -> None:
        source = FIRMWARE.read_text(encoding="utf-8")
        try:
            connect_body = function_body(source, "void connectToSta(")
            association_body = function_body(source, "void processStaAssociation(")
        except AssertionError as error:
            self.fail(
                "Production firmware must expose nonblocking void connectToSta and "
                "processStaAssociation definitions; the old bool/blocking shape is "
                f"unsupported ({error})."
            )

        compiler = shutil.which("g++")
        if compiler is None:
            if os.name == "posix":
                self.fail("g++ is required for the cloud behavioral regression test")
            self.skipTest("g++ is unavailable on this non-Linux runner")

        with tempfile.TemporaryDirectory(prefix="sta-association-") as temp_dir:
            temp_path = Path(temp_dir)
            harness = temp_path / "sta_association_behavior.cpp"
            binary = temp_path / "sta_association_behavior"
            harness.write_text(
                harness_source(connect_body, association_body), encoding="utf-8"
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
