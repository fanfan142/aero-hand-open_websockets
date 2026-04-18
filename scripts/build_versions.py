#!/usr/bin/env python3
"""
为每个固件版本创建PlatformIO项目并编译左右手固件
"""

import os
import shutil
import subprocess
import pathlib

# 获取脚本所在目录的父目录（即仓库根目录）
SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
BASE_DIR = REPO_ROOT
FIRMWARE_WS = BASE_DIR / "firmware_ws"
BUILD_DIR = BASE_DIR / "firmware_ws_build"

VERSIONS = ['v0.1.0', 'v0.1.3', 'v0.1.4', 'v0.1.5', 'v0.2.0']
HANDS = ['lefthand', 'righthand']

PLATFORMIO_INI_TEMPLATE = '''; PlatformIO Project Configuration File
; Aero Hand WebSocket Firmware - {version} ({hand})

[env:esp32-s3-devkitc-1]
platform = espressif32
board = esp32-s3-devkitc-1
framework = arduino
upload_speed = 921600
monitor_speed = 115200

build_flags =
    -D ARDUINO_USB_MODE=1
    -D ARDUINO_USB_CDC_ON_BOOT=1
    -DCORE_DEBUG_LEVEL=3
    -Wno-reorder
    -D{hand_flag}

lib_deps =
    links2004/WebSockets@^2.3.4
    bblanchon/ArduinoJson@^6.21.3

upload_resetmethod = esptool
'''

def create_project(version, hand):
    proj_dir = BUILD_DIR / version / hand
    src_dir = proj_dir / "src"
    lib_dir = proj_dir / "lib"

    # Create directories
    os.makedirs(src_dir, exist_ok=True)
    os.makedirs(lib_dir, exist_ok=True)

    # Copy HLSCL library
    hlscl_src = BUILD_DIR / "lib" / "HLSCL"
    hlscl_dst = lib_dir / "HLSCL"
    if hlscl_src.exists():
        shutil.copytree(hlscl_src, hlscl_dst, dirs_exist_ok=True)

    # Copy source files
    firmware_src = FIRMWARE_WS / version
    print(f"  Source: {firmware_src}")
    for f in os.listdir(firmware_src):
        src_path = firmware_src / f
        if src_path.is_file():
            shutil.copy2(src_path, src_dir / f)
            print(f"    Copied: {f}")

    # Copy shared lib files from firmware_ws/lib/
    lib_src = FIRMWARE_WS / "lib"
    if lib_src.is_dir():
        for f in os.listdir(lib_src):
            src_path = lib_src / f
            if src_path.is_file():
                shutil.copy2(src_path, src_dir / f)
                print(f"    Copied lib: {f}")

    # Determine hand flag
    hand_flag = "LEFT_HAND" if hand == "lefthand" else "RIGHT_HAND"

    # Create platformio.ini
    ini_path = proj_dir / "platformio.ini"
    with open(ini_path, 'w', encoding='utf-8') as f:
        f.write(PLATFORMIO_INI_TEMPLATE.format(version=version, hand=hand, hand_flag=hand_flag))

    return proj_dir

def build_project(version, hand):
    proj_dir = BUILD_DIR / version / hand
    print(f"\n=== Building {version} {hand} ===")

    try:
        result = subprocess.run(
            ['pio', 'run'],
            cwd=str(proj_dir),
            capture_output=True,
            text=True,
            timeout=600
        )
        if result.returncode == 0:
            print(f"  Build successful!")
            # Find generated bin files
            build_dir = proj_dir / ".pio" / "build" / "esp32-s3-devkitc-1"
            if build_dir.exists():
                for f in os.listdir(build_dir):
                    if f.endswith('.bin') and not f.endswith('bootloader.bin') and not f.endswith('partitions.bin'):
                        src_path = build_dir / f
                        dst_path = BUILD_DIR / f"firmware_{version}_{hand}.bin"
                        shutil.copy2(src_path, dst_path)
                        print(f"    Copied: {f} -> firmware_{version}_{hand}.bin")
            return True
        else:
            print(f"  Build failed:")
            print(result.stderr[-2000:] if len(result.stderr) > 2000 else result.stderr)
            return False
    except subprocess.TimeoutExpired:
        print(f"  Build timed out!")
        return False
    except Exception as e:
        print(f"  Build error: {e}")
        return False

def main():
    print("Creating PlatformIO projects for each version and hand...\n")

    # Create all projects
    for version in VERSIONS:
        for hand in HANDS:
            print(f"\n=== {version}/{hand} ===")
            proj_dir = create_project(version, hand)
            print(f"  Project created: {proj_dir}")

    print("\n" + "="*60)
    print("All projects created. Building left/right for each version...")
    print("="*60)

    success_count = 0
    total_count = len(VERSIONS) * len(HANDS)

    for version in VERSIONS:
        for hand in HANDS:
            if build_project(version, hand):
                success_count += 1

    print(f"\n=== Build complete: {success_count}/{total_count} successful ===")

    if success_count == total_count:
        print("\nAll firmware built successfully!")
        print("Bin files are in:")
        for version in VERSIONS:
            for hand in HANDS:
                bin_path = BUILD_DIR / f"firmware_{version}_{hand}.bin"
                if bin_path.exists():
                    print(f"  {bin_path}")

if __name__ == '__main__':
    main()
