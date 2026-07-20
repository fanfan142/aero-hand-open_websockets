# Aero Hand Open - WebSocket Communication Suite

<p align="center">
  <img src="https://img.shields.io/badge/Base%20Project-Aero%20Hand%20Open-blue" alt="Base Project">
  <img src="https://img.shields.io/badge/License-Apache--2.0-green" alt="License">
</p>

<p align="center">
  <strong>WebSocket communication solutions for the Aero Hand Open robotic hand</strong>
</p>

---

## Overview

This project is a derivative of [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open). It provides an Android controller, ESP32-S3 WebSocket firmware, a Python bridge, a C ABI library, and one shared JSON protocol.

> **Firmware compatibility warning:** do not use the historical `firmware_bin/aero_hand_wifi.ino.bin` or merged image with the current Android app. Those checked-in images contain only the legacy control protocol and are missing `actuator_control` plus every app provisioning command. Full provisioning requires the hand-specific `firmware_v0.2.0_*.bin` from Release `v1.5.5` or newer, or a Firmware CI artifact built from the same commit as the app. The `v0.2.0` filename alone does not prove compatibility: the app enables correlated-ACK provisioning only when firmware explicitly reports `protocol_version >= 2`. Legacy motion control still falls back to `multi_joint_control`.

---

## Components
| Component | Purpose | Connection |
|-----------|---------|------------|
| Android app | WiFi/USB control, presets, gesture following, AP-to-STA provisioning | WiFi WebSocket or USB OTG |
| ESP32 firmware | WebSocket server and seven-actuator hand control | ESP32-S3 to servo bus |
| Python bridge | Wired server, scripting, and fake-mode protocol testing | USB to servo controller |
| C DLL | C/C++/C#/ctypes integration | WebSocket to ESP32 |
| Shared protocol | Control, status, capability, and provisioning contract | JSON over WebSocket |

---

## Android App

The app automatically detects firmware capabilities. Current `firmware_ws` releases use `actuator_control`; unknown legacy firmware falls back to `multi_joint_control`. Provisioning is enabled only for firmware that reports the complete static-network status contract.

The gesture path uses CameraX RGBA frames and MediaPipe. Conversion reuses bitmap and byte buffers, limits detection images to a 256-pixel longest side, seeds smoothing from the first frame, and sends control independently from the 10 Hz Compose UI refresh. Calibration is isolated by handedness, camera facing, and mirror mode; schema 5 invalidates older calibration records.

Download `HandControl-*.apk` from GitHub Releases. Builds from `main` are signed preview artifacts produced by Android CI; pull requests build a separate debug-signed APK.

---

## Solution 1: Python Bridge (Wired)

Run Python WebSocket server locally, connect to servo controller via USB.

**Connection**: PC → USB → Servo Controller → Robotic Hand

**Firmware**: Use original USB firmware, no ESP32 firmware needed.

**Directory**: `python_bridge/`

```bash
cd python_bridge
pip install -e .

# Run server (fake mode, no hardware needed)
python -m aero_ws_python.server --fake

# Run server (with real hardware)
python -m aero_ws_python.server --serial COM3 --baudrate 115200
```

**Features**: Simple, easy to develop and debug, low latency, stable and reliable.

---

## Solution 2: ESP32 WiFi (Wireless)

ESP32 runs WebSocket server independently for wireless control.

**Connection**: Phone/PC → WiFi → ESP32 → Serial → Servo Controller → Robotic Hand

**Firmware**: ESP32 WiFi firmware needs to be flashed.

**Directory**: `esp32_wifi/`

### Flash Firmware

Download the hand-specific application image from Release `v1.5.5` or newer, or use a Firmware CI artifact built from the same commit as the app, and flash it at `0x10000`:

```bash
esptool.py --chip esp32s3 --port COM3 write_flash \
  0x10000 firmware_v0.2.0_lefthand.bin
```

Use `firmware_v0.2.0_righthand.bin` for a right hand. A left/right mismatch can apply the wrong homing and actuator direction configuration. The `firmware_bin/` directory is retained only for historical inspection and is not a supported flashing source for the current app.

### Usage

1. Power on ESP32; the hand-specific firmware creates `AeroHand_Left` or `AeroHand_Right`
2. Connect phone/PC to the hotspot
3. Connect the Android app or another client to `ws://192.168.4.1:8765`

### Firmware Files

| File | Description |
|------|-------------|
| `firmware_v0.2.0_lefthand.bin` | Left-hand application image; use the `v1.5.5+` Release or same-commit CI artifact |
| `firmware_v0.2.0_righthand.bin` | Right-hand application image; use the `v1.5.5+` Release or same-commit CI artifact |
| `firmware_v0.1.x_*.bin` | Historical WebSocket variants; static-IP provisioning is not supported by the current app |

### WiFi Configuration

The Android app can provision a 2.4 GHz SSID, an open or WPA password, static IP, gateway, subnet, and DNS values. It validates the network first, then waits for a `wifi_config_set` ACK and a `wifi_status` carrying the same `request_id`, SSID, and static IP. Only then does it send `wifi_connect_sta` and wait for its matching ACK.

Source defaults are in `esp32_wifi/firmware/aero_hand_wifi/config.h`:

```cpp
#define WIFI_MODE 1           // 1=AP mode, 2=STA mode
#define AP_SSID "AeroHand_WIFI"
#define AP_PASSWORD "12345678"
#define WS_PORT 8765
```

---

## Solution 3: C DLL Library (Cross-language)

Compile to dynamic library, supports C/C++, C#, Python (ctypes), etc.

**Firmware**: The DLL itself needs no firmware, but as a WebSocket **client**, it requires ESP32 WiFi solution (Solution 2) to be deployed first.

**Directory**: `c_dll/`

### Build

```bash
# Linux/macOS
mkdir build && cd build
cmake ..
make

# Windows
mkdir build
cmake .. -G "Visual Studio 17 2022"
cmake --build . --config Release
```

### API Example

```c
#include "aero_ws.h"

AeroWSHandle handle = aero_ws_create("192.168.4.1", 8765);
aero_ws_connect(handle, 5000);
aero_ws_set_joint(handle, "index_proximal", 45.0f, 500);
aero_ws_disconnect(handle);
aero_ws_destroy(handle);
```

---

## Joint Definitions

Aero Hand Open has **15 joints**:

| Joint ID | Description | Servo ID | Angle Range |
|----------|-------------|----------|------------|
| thumb_proximal | Thumb proximal | 0 | 0° ~ 90° |
| thumb_distal | Thumb distal | 1 | 0° ~ 90° |
| index_proximal | Index proximal | 2 | 0° ~ 90° |
| index_middle | Index middle | 3 | 0° ~ 90° |
| index_distal | Index distal | 4 | 0° ~ 90° |
| middle_proximal | Middle proximal | 5 | 0° ~ 90° |
| middle_middle | Middle middle | 6 | 0° ~ 90° |
| middle_distal | Middle distal | 7 | 0° ~ 90° |
| ring_proximal | Ring proximal | 8 | 0° ~ 90° |
| ring_middle | Ring middle | 9 | 0° ~ 90° |
| ring_distal | Ring distal | 10 | 0° ~ 90° |
| pinky_proximal | Pinky proximal | 11 | 0° ~ 90° |
| pinky_middle | Pinky middle | 12 | 0° ~ 90° |
| pinky_distal | Pinky distal | 13 | 0° ~ 90° |
| thumb_rotation | Thumb rotation | 14 | -30° ~ 30° |

---

## Communication Protocol

See [`protocol/CONTROL_PROTOCOL.md`](protocol/CONTROL_PROTOCOL.md)

---

## Firmware Version Notes

### Original Firmware Source (`firmware_src/`)

Original firmware source code from upstream [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open):

| Version | Status | Description |
|---------|--------|-------------|
| v0.1.0 | ✅ Available | Initial release |
| v0.1.1 | ❌ Unavailable | Only binary firmware available |
| v0.1.2 | ❌ Unavailable | Only binary firmware available |
| v0.1.3 | ✅ Available | Added thermal protection |
| v0.1.4 | ✅ Available | Optimized thermal protection |
| v0.1.5 | ✅ Available | Fixed motor configuration |
| v0.2.0 | ✅ Available | Thumb homing offset optimization |

### WebSocket Firmware (`firmware_ws/`)

Original serial firmware converted to WiFi + WebSocket communication, preserving each version's characteristics. Current binaries are produced from source by GitHub Actions and attached to versioned Releases:

| Version | Left-hand Binary | Right-hand Binary | Thermal Protection | Initial Torque | Features |
|---------|------------------|-------------------|-------------------|----------------|----------|
| v0.1.0 | `firmware_v0.1.0_lefthand.bin` | `firmware_v0.1.0_righthand.bin` | None | 1023 | Base version |
| v0.1.3 | `firmware_v0.1.3_lefthand.bin` | `firmware_v0.1.3_righthand.bin` | 50°C / 200 | 700 | Added thermal protection |
| v0.1.4 | `firmware_v0.1.4_lefthand.bin` | `firmware_v0.1.4_righthand.bin` | 70°C / 500 | 700 | More relaxed thermal protection |
| v0.1.5 | `firmware_v0.1.5_lefthand.bin` | `firmware_v0.1.5_righthand.bin` | 70°C / 500 | 700 | Fixed motor configuration |
| v0.2.0 | `firmware_v0.2.0_lefthand.bin` | `firmware_v0.2.0_righthand.bin` | 70°C / 500 | 700 | Thumb homing offset optimization |

See [`firmware_ws/README.md`](firmware_ws/README.md) for details.

---

## Project Structure

```
aero-hand-open_websockets/
├── python_bridge/           # Solution 1: Python WebSocket Bridge (Wired)
│   ├── aero_ws_python/    # Python package source
│   ├── examples/          # Example scripts
│   ├── tests/            # Unit tests
│   └── README.md
│
├── android/               # Android WiFi/USB and gesture controller
├── esp32_wifi/            # Standalone ESP32 WiFi firmware source
│   ├── firmware/          # ESP32 firmware source
│   │   └── aero_hand_wifi/
│   ├── web_client/        # Web client (HTML)
│   └── README.md
│
├── c_dll/                 # Solution 3: C DLL Library (Cross-language)
│   ├── include/           # Header files
│   ├── src/              # Source code
│   └── examples/          # Usage examples
│
├── firmware_bin/           # Historical Arduino artifacts, not current app firmware
├── firmware_src/           # Original firmware source (v0.1.0, v0.1.3-v0.2.0)
├── firmware_ws/           # WebSocket converted firmware (v0.1.0, v0.1.3-v0.2.0)
├── protocol/              # Unified communication protocol
├── scripts/               # Cloud firmware build and contract checks
└── README.md / README_en.md
```

---

## Testing

Static tests can be run without compiling Android or firmware:

```bash
python -m pytest python_bridge/tests scripts/tests -q
python scripts/check_firmware_contract.py esp32_wifi/firmware/aero_hand_wifi firmware_ws/v0.2.0
```

GitHub Actions is the release build gate:

- Android CI: Python tests, Android JVM tests, and a signed preview APK from `main`
- Firmware CI: checker tests, standalone firmware, v0.2.0 left/right builds, compiled-binary contract checks, and firmware artifacts
- Tag workflows: signed Android APK plus release firmware binaries

Cloud compilation does not replace hardware validation. AP-to-STA switching, legacy fallback, left/right recalibration, gesture FPS/GC, and continuous servo response must still be checked on devices.

---

## Relationship with Upstream

This project is derived from [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open).

| Component | Upstream | This Project |
|-----------|----------|--------------|
| Mechanical hardware | ✅ | - |
| USB firmware | ✅ | - |
| Python bridge | - | ✅ WebSocket extension |
| ESP32 WiFi firmware | - | ✅ New |
| C DLL library | - | ✅ New |

---

## License

- **Software code**: Apache-2.0
- **Upstream hardware design files**: CC BY-NC-SA 4.0

See [LICENSE.md](LICENSE.md)

---

## Links

- **This Project**: https://github.com/fanfan142/aero-hand-open_websockets
- **Base Project**: https://github.com/TetherIA/aero-hand-open

---

© 2026 fanfan142 | Based on [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)
