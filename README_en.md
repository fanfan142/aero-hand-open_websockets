# Aero Hand Open - WebSocket Communication Suite

<p align="center">
  <img src="https://img.shields.io/badge/Base%20Project-Aero%20Hand%20Open-blue" alt="Base Project">
  <img src="https://img.shields.io/badge/License-Apache--2.0-green" alt="License">
  <img src="https://img.shields.io/badge/Python-3.8+-orange" alt="Python">
  <img src="https://img.shields.io/badge/ESP32-S3-brightgreen" alt="ESP32">
</p>

<p align="center">
  <strong>WebSocket communication solutions for the Aero Hand Open robotic hand</strong>
</p>

---

## Overview

This project extends the [Aero Hand Open](https://github.com/TetherIA/aero-hand-open) robotic hand with comprehensive WebSocket communication capabilities. It enables wireless control, cross-platform integration, and multi-language support through a unified JSON-based protocol.

### Key Features

- **WiFi Wireless Control** - Control your Aero Hand Open over WiFi using ESP32-S3
- **Python SDK** - Full Python package with WebSocket server and client
- **C DLL Library** - Cross-language support for C/C++, C#, Python (ctypes)
- **Browser Control** - HTML-based control panel for quick testing
- **Unified Protocol** - JSON-based communication for 15 joints

---

## Project Structure

```
aero-hand-open_websockets/
├── aero_ws_python/          # Python WebSocket SDK
│   ├── __init__.py          # Package entry
│   ├── client.py            # WebSocket client
│   ├── server.py            # WebSocket server
│   ├── protocol.py          # Protocol definitions
│   ├── examples/            # Usage examples
│   │   ├── html_client.html # Browser control panel
│   │   ├── test_client.py  # Basic test
│   │   ├── interactive_test.py  # Interactive shell
│   │   └── connection_test.py   # Connection test
│   └── tests/               # Unit tests
│
├── c_dll/                   # C DLL library
│   ├── include/             # Header files
│   │   ├── aero_ws.h       # Public API
│   │   └── aero_ws_internal.h
│   ├── src/                # Source implementation
│   │   └── aero_ws.c
│   └── examples/           # Usage examples
│       ├── python_wrapper.py  # Python ctypes example
│       └── csharp_example.cs # C# P/Invoke example
│
├── esp32_firmware/          # ESP32 WiFi firmware
│   └── aero_hand_wifi/     # Arduino project
│       ├── aero_hand_wifi.ino  # Main sketch
│       ├── config.h        # WiFi configuration
│       ├── webSocketServer.* # WebSocket handler
│       ├── servoControl.*  # Servo control
│       ├── SCS.*           # Serial communication
│       ├── Homing.*        # Calibration
│       └── README.md       # Firmware docs
│
├── firmware_bin/            # Pre-built firmware
│   ├── aero_hand_wifi.ino.merged.bin  # Full firmware (8MB)
│   ├── aero_hand_wifi.ino.bin         # Application
│   ├── aero_hand_wifi.ino.bootloader.bin
│   ├── aero_hand_wifi.ino.partitions.bin
│   └── boot_app0.bin
│
└── protocol/               # Communication protocol
    └── CONTROL_PROTOCOL.md # JSON protocol docs
```

---

## Quick Start

### Option A: Python WebSocket Bridge (Recommended for Development)

```bash
cd aero_ws_python
pip install -e .

# Run server (fake mode, no hardware needed)
python -m aero_ws_python.server --fake

# Or connect to real hardware
python -m aero_ws_python.server --serial COM3 --baudrate 115200
```

### Option B: ESP32 Standalone Firmware (Wireless Control)

1. Flash the firmware using esptool:
```bash
esptool.py --chip esp32s3 --port COM3 write_flash 0x0 firmware_bin/boot_app0.bin 0x1000 firmware_bin/aero_hand_wifi.ino.bootloader.bin 0x8000 firmware_bin/aero_hand_wifi.ino.partitions.bin 0x10000 firmware_bin/aero_hand_wifi.ino.merged.bin
```

2. Connect to WiFi hotspot `AeroHand_WIFI` (password: `12345678`)

3. Open `aero_ws_python/examples/html_client.html` in browser

### Option C: C DLL Library

```bash
# Build on Linux/macOS
mkdir build && cd build
cmake ..
make

# Or build on Windows with Visual Studio
cmake .. -G "Visual Studio 17 2022"
cmake --build . --config Release
```

---

## Joint Definitions

The Aero Hand Open has **15 joints**:

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

### Control Command Format

```json
{
  "type": "multi_joint_control",
  "timestamp": 1711641600000,
  "data": {
    "joints": [
      {"joint_id": "index_proximal", "angle": 45.0},
      {"joint_id": "index_middle", "angle": 30.0},
      {"joint_id": "index_distal", "angle": 15.0}
    ],
    "duration_ms": 500
  }
}
```

See [`protocol/CONTROL_PROTOCOL.md`](protocol/CONTROL_PROTOCOL.md) for full documentation.

---

## API Reference

### Python SDK

```python
from aero_ws_python import AeroWebSocketClient, AeroWebSocketServer

# Client usage
client = AeroWebSocketClient("192.168.4.1", 8765)
client.connect()
client.set_multi_joints([
    {"joint_id": "index_proximal", "angle": 45.0},
    {"joint_id": "index_middle", "angle": 30.0}
], duration_ms=500)
client.disconnect()

# Server usage
server = AeroWebSocketServer(port=8765, serial_port="COM3")
asyncio.run(server.start())
```

### C DLL

```c
#include "aero_ws.h"

AeroWSHandle handle = aero_ws_create("192.168.4.1", 8765);
aero_ws_connect(handle, 5000);
aero_ws_set_joint(handle, "index_proximal", 45.0f, 500);
aero_ws_disconnect(handle);
aero_ws_destroy(handle);
```

---

## Hardware Requirements

### For ESP32 Firmware

- **Board**: Seeed XIAO ESP32S3 or compatible
- **Power**: 12V DC input with 5V regulator
- **Connection**: TX/GPIO43 → RX of servo control board, RX/GPIO44 → TX

### For Python Bridge

- USB connection to servo control board
- Python 3.8+
- pyserial library

---

## Testing

```bash
cd aero_ws_python
pip install pytest
python -m pytest tests/ -v
```

Current test status: **7 passed**

---

## Relationship with Upstream

This project is a **derivative project** of [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open), focused on extending the communication capabilities:

| Component | Upstream | This Project |
|-----------|----------|--------------|
| Mechanical hardware | ✅ Full | - |
| USB firmware | ✅ Full | - |
| Python SDK | ✅ Serial | **WebSocket extension** |
| ESP32 firmware | ❌ None | **✅ WiFi WebSocket** |
| C DLL | ❌ None | **✅ Cross-language** |

---

## License

- **Software code (Python, C, ESP32 firmware)**: [Apache-2.0](LICENSE.md)
- **Communication protocol documentation**: [Apache-2.0](LICENSE.md)

This project is derived from [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open), whose hardware design files are licensed under **CC BY-NC-SA 4.0**.

See [LICENSE.md](LICENSE.md) for full details.

---

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## Acknowledgments

- **TetherIA** - For designing and open-sourcing the Aero Hand Open robotic hand
- **Project URL**: https://github.com/TetherIA/aero-hand-open

---

## Links

- **This Project**: https://github.com/fanfan142/aero-hand-open_websockets
- **Base Project**: https://github.com/TetherIA/aero-hand-open
- **Documentation**: https://docs.tetheria.ai/

---

© 2026 fanfan142 | Based on [TetherIA/aero-hand-open](https://github.com/TetherIA/aero-hand-open)
