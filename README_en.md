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

This project is a derivative of [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open), providing **3 independent WebSocket communication solutions**.

---

## Three Solutions Overview

| Solution | Description | Firmware | Connection |
|----------|-------------|----------|------------|
| **Sol 1** | Python Bridge (Wired) | None (use original USB) | USB → Servo controller |
| **Sol 2** | ESP32 WiFi (Wireless) | Need flash ESP32 firmware | WiFi hotspot |
| **Sol 3** | C DLL Library (Cross-lang) | None (library only) | WiFi → ESP32 server |

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

Use pre-built firmware in `firmware_bin/`:

```bash
esptool.py --chip esp32s3 --port COM3 write_flash \
  0x0 firmware_bin/boot_app0.bin \
  0x1000 firmware_bin/aero_hand_wifi.ino.bootloader.bin \
  0x8000 firmware_bin/aero_hand_wifi.ino.partitions.bin \
  0x10000 firmware_bin/aero_hand_wifi.ino.merged.bin
```

### Usage

1. Power on ESP32, it creates hotspot `AeroHand_WIFI` (password: `12345678`)
2. Connect phone/PC to the hotspot
3. Open `esp32_wifi/web_client/html_client.html` or use Python client to connect `ws://192.168.4.1:8765`

### Firmware Files

| File | Description |
|------|-------------|
| `aero_hand_wifi.ino.merged.bin` | Full firmware (8MB), includes all partitions, recommended |
| `aero_hand_wifi.ino.bin` | Application only (no bootloader) |
| `aero_hand_wifi.ino.bootloader.bin` | Bootloader |
| `aero_hand_wifi.ino.partitions.bin` | Partition table |
| `boot_app0.bin` | Boot app0 |

### WiFi Configuration

Modify in `esp32_wifi/firmware/aero_hand_wifi/config.h`:

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

Original serial firmware converted to WiFi + WebSocket communication, preserving each version's characteristics:

| Version | Thermal Protection | Initial Torque | Features |
|---------|-------------------|----------------|----------|
| v0.1.0 | None | 1023 | Base version |
| v0.1.3 | 50°C / 200 | 700 | Added thermal protection |
| v0.1.4 | 70°C / 500 | 700 | More relaxed thermal protection |
| v0.1.5 | 70°C / 500 | 700 | Fixed motor configuration |
| v0.2.0 | 70°C / 500 | 700 | Thumb homing offset optimization |

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
├── esp32_wifi/            # Solution 2: ESP32 WiFi Control (Wireless, verified)
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
├── firmware_bin/           # Pre-built ESP32 firmware
├── firmware_src/           # Original firmware source (v0.1.0, v0.1.3-v0.2.0)
├── firmware_ws/           # WebSocket converted firmware (v0.1.0, v0.1.3-v0.2.0)
├── protocol/              # Unified communication protocol
└── README.md / README_en.md
```

---

## Testing

```bash
cd python_bridge
pip install pytest
python -m pytest tests/ -v
```

Current status: **7 passed**

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
