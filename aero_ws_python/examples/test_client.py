"""
Aero Hand WebSocket Test Client
测试客户端脚本

用法:
    python test_client.py --host localhost --port 8765

    # 或使用 fake 模式（不需要实际硬件）
    python test_client.py --fake
"""

import argparse
import time
import sys
import os

# 添加父目录到路径，以便导入aero_ws_python
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from aero_ws_python import AeroWebSocketClient


def test_sequence(client: AeroWebSocketClient, fake_mode: bool = False):
    """执行测试序列"""

    if not fake_mode and not client.connect():
        print("Failed to connect to server")
        return False

    print("\n=== Aero Hand WebSocket Test ===\n")

    # Test 1: Single joint control
    print("Test 1: Single joint control (index_proximal -> 45°)")
    client.set_joint("index_proximal", 45.0, duration_ms=500)
    time.sleep(1)

    # Test 2: Multi joint control
    print("Test 2: Multi joint control")
    client.set_multi_joints([
        {"joint_id": "index_proximal", "angle": 30.0},
        {"joint_id": "index_middle", "angle": 20.0},
        {"joint_id": "index_distal", "angle": 10.0},
        {"joint_id": "middle_proximal", "angle": 45.0},
    ], duration_ms=500)
    time.sleep(1)

    # Test 3: All fingers wave
    print("Test 3: Wave motion")
    for i in range(3):
        client.set_multi_joints([
            {"joint_id": "index_proximal", "angle": 60.0},
            {"joint_id": "middle_proximal", "angle": 60.0},
            {"joint_id": "ring_proximal", "angle": 60.0},
            {"joint_id": "pinky_proximal", "angle": 60.0},
        ], duration_ms=200)
        time.sleep(0.3)
        client.set_multi_joints([
            {"joint_id": "index_proximal", "angle": 0.0},
            {"joint_id": "middle_proximal", "angle": 0.0},
            {"joint_id": "ring_proximal", "angle": 0.0},
            {"joint_id": "pinky_proximal", "angle": 0.0},
        ], duration_ms=200)
        time.sleep(0.3)

    # Test 4: Thumb motion
    print("Test 4: Thumb motion")
    client.set_joint("thumb_proximal", 45.0, duration_ms=500)
    time.sleep(0.5)
    client.set_joint("thumb_distal", 30.0, duration_ms=300)
    time.sleep(1)

    # Test 5: Homing
    print("Test 5: Homing (all zeros)")
    client.homing()
    time.sleep(1)

    print("\n=== Test Complete ===")
    return True


def run_fake_mode():
    """模拟运行（不需要硬件）"""
    print("\n=== Aero Hand Fake Mode Test ===\n")
    print("This test runs without actual hardware connection.")
    print("Commands are printed but not actually sent.\n")

    from aero_ws_python.protocol import JOINT_TO_SERVO_ID

    commands = [
        {"type": "joint_control", "data": {"joint_id": "index_proximal", "angle": 45.0, "duration_ms": 500}},
        {"type": "multi_joint_control", "data": {
            "joints": [
                {"joint_id": "index_proximal", "angle": 30.0},
                {"joint_id": "index_middle", "angle": 20.0},
                {"joint_id": "index_distal", "angle": 10.0},
            ],
            "duration_ms": 500
        }},
        {"type": "homing", "timestamp": int(time.time() * 1000)},
    ]

    for i, cmd in enumerate(commands, 1):
        print(f"[{i}] Would send: {cmd}")
        time.sleep(0.5)

    print("\n=== Fake Test Complete ===")
    print("\nTo run with real hardware:")
    print("  python test_client.py --host 192.168.1.100 --port 8765")


def main():
    parser = argparse.ArgumentParser(description="Aero Hand WebSocket Test Client")
    parser.add_argument("--host", default="localhost", help="Server host")
    parser.add_argument("--port", type=int, default=8765, help="Server port")
    parser.add_argument("--fake", action="store_true", help="Run in fake mode (no hardware)")
    args = parser.parse_args()

    if args.fake:
        run_fake_mode()
        return

    client = AeroWebSocketClient(args.host, args.port)
    test_sequence(client)


if __name__ == "__main__":
    main()
