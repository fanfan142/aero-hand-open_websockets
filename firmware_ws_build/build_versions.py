#!/usr/bin/env python3
"""
为每个固件版本创建PlatformIO项目并编译左右手固件
"""

import os
import shutil
import subprocess

BASE_DIR = r"F:\sim\aero\aero-hand-open\project\aero-hand-open_websockets_work"
FIRMWARE_WS = os.path.join(BASE_DIR, "firmware_ws")
BUILD_DIR = os.path.join(BASE_DIR, "firmware_ws_build")

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
    proj_dir = os.path.join(BUILD_DIR, version, hand)
    src_dir = os.path.join(proj_dir, "src")
    lib_dir = os.path.join(proj_dir, "lib")

    # Create directories
    os.makedirs(src_dir, exist_ok=True)
    os.makedirs(lib_dir, exist_ok=True)

    # Copy HLSCL library
    hlscl_src = os.path.join(BUILD_DIR, "lib", "HLSCL")
    hlscl_dst = os.path.join(lib_dir, "HLSCL")
    if os.path.exists(hlscl_src):
        shutil.copytree(hlscl_src, hlscl_dst, dirs_exist_ok=True)

    # Copy source files
    firmware_src = os.path.join(FIRMWARE_WS, version)
    print(f"  Source: {firmware_src}")
    for f in os.listdir(firmware_src):
        src_path = os.path.join(firmware_src, f)
        if os.path.isfile(src_path):
            shutil.copy2(src_path, os.path.join(src_dir, f))
            print(f"    Copied: {f}")

    # Copy shared lib files from firmware_ws/lib/
    lib_src = os.path.join(FIRMWARE_WS, "lib")
    if os.path.isdir(lib_src):
        for f in os.listdir(lib_src):
            src_path = os.path.join(lib_src, f)
            if os.path.isfile(src_path):
                shutil.copy2(src_path, os.path.join(src_dir, f))
                print(f"    Copied lib: {f}")

    # Determine hand flag
    hand_flag = "LEFT_HAND" if hand == "lefthand" else "RIGHT_HAND"

    # Create platformio.ini
    ini_path = os.path.join(proj_dir, "platformio.ini")
    with open(ini_path, 'w', encoding='utf-8') as f:
        f.write(PLATFORMIO_INI_TEMPLATE.format(version=version, hand=hand, hand_flag=hand_flag))

    return proj_dir

def build_project(version, hand):
    proj_dir = os.path.join(BUILD_DIR, version, hand)
    print(f"\n=== Building {version} {hand} ===")

    try:
        result = subprocess.run(
            ['pio', 'run'],
            cwd=proj_dir,
            capture_output=True,
            text=True,
            timeout=600
        )
        if result.returncode == 0:
            print(f"  Build successful!")
            # Find generated bin files
            build_dir = os.path.join(proj_dir, ".pio", "build", "esp32-s3-devkitc-1")
            if os.path.exists(build_dir):
                for f in os.listdir(build_dir):
                    if f.endswith('.bin') and not f.endswith('bootloader.bin') and not f.endswith('partitions.bin'):
                        src_path = os.path.join(build_dir, f)
                        # Clean version string for filename
                        version_clean = version.replace('.', '')
                        dst_path = os.path.join(BUILD_DIR, f"firmware_{version}_{hand}.bin")
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
                bin_path = os.path.join(BUILD_DIR, f"firmware_{version}_{hand}.bin")
                if os.path.exists(bin_path):
                    print(f"  {bin_path}")

if __name__ == '__main__':
    main()
