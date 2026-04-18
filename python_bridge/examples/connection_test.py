#!/usr/bin/env python3
"""
Aero Hand 连接测试脚本
快速验证ESP32固件是否正常工作

用法:
    python connection_test.py
    python connection_test.py --host 192.168.4.1
"""

import argparse
import sys
import os
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from aero_ws_python import AeroWebSocketClient
from aero_ws_python.protocol import JOINT_TO_SERVO_ID


def print_result(test_name, passed, detail=""):
    """打印测试结果"""
    status = "✓ 通过" if passed else "✗ 失败"
    color_code = "\033[92m" if passed else "\033[91m"
    reset = "\033[0m"
    print(f"{color_code}{status}{reset} - {test_name}")
    if detail:
        print(f"      {detail}")


def test_connection(client):
    """测试1: WebSocket连接"""
    print("\n[测试 1] WebSocket 连接")
    print("-" * 40)

    try:
        if client.connect(timeout=5.0):
            print_result("连接成功", True, f"已连接到 {client.host}:{client.port}")
            return True
        else:
            print_result("连接失败", False, "请检查ESP32是否正常工作")
            return False
    except Exception as e:
        print_result("连接异常", False, str(e))
        return False


def test_single_joint(client):
    """测试2: 单关节控制"""
    print("\n[测试 2] 单关节控制")
    print("-" * 40)

    test_joints = [
        ("index_proximal", 45, "食指-近端 -> 45°"),
        ("thumb_proximal", 30, "拇指-近端 -> 30°"),
    ]

    for joint_id, angle, desc in test_joints:
        try:
            result = client.set_joint(joint_id, angle, duration_ms=500)
            print_result(desc, result)
            time.sleep(0.5)
        except Exception as e:
            print_result(desc, False, str(e))

    return True


def test_multi_joint(client):
    """测试3: 多关节控制"""
    print("\n[测试 3] 多关节同步控制")
    print("-" * 40)

    joints = [
        {"joint_id": "index_proximal", "angle": 30},
        {"joint_id": "index_middle", "angle": 20},
        {"joint_id": "index_distal", "angle": 10},
        {"joint_id": "middle_proximal", "angle": 30},
    ]

    try:
        result = client.set_multi_joints(joints, duration_ms=500)
        print_result("多关节同步控制", result, f"发送了 {len(joints)} 个关节指令")
        return result
    except Exception as e:
        print_result("多关节同步控制", False, str(e))
        return False


def test_homing(client):
    """测试4: 归零指令"""
    print("\n[测试 4] 归零指令")
    print("-" * 40)

    try:
        result = client.homing()
        print_result("归零指令", result)
        return result
    except Exception as e:
        print_result("归零指令", False, str(e))
        return False


def test_all_joints(client):
    """测试5: 所有关节遍历"""
    print("\n[测试 5] 所有关节遍历测试")
    print("-" * 40)

    passed = 0
    failed = 0

    for joint_id in JOINT_TO_SERVO_ID.keys():
        try:
            result = client.set_joint(joint_id, 45, duration_ms=300)
            if result:
                passed += 1
                print(f"  ✓ {joint_id}")
            else:
                failed += 1
                print(f"  ✗ {joint_id}")
            time.sleep(0.2)
        except Exception as e:
            failed += 1
            print(f"  ✗ {joint_id} - {e}")

    print_result(f"遍历测试", failed == 0, f"通过: {passed}, 失败: {failed}")
    return failed == 0


def test_motion_sequences(client):
    """测试6: 动作序列测试"""
    print("\n[测试 6] 动作序列测试")
    print("-" * 40)

    sequences = [
        ("抓取动作", [
            {"joint_id": "thumb_proximal", "angle": 60},
            {"joint_id": "thumb_distal", "angle": 80},
            {"joint_id": "index_proximal", "angle": 80},
            {"joint_id": "index_middle", "angle": 90},
            {"joint_id": "index_distal", "angle": 90},
            {"joint_id": "middle_proximal", "angle": 80},
            {"joint_id": "middle_middle", "angle": 90},
            {"joint_id": "middle_distal", "angle": 90},
            {"joint_id": "ring_proximal", "angle": 80},
            {"joint_id": "ring_middle", "angle": 90},
            {"joint_id": "ring_distal", "angle": 90},
            {"joint_id": "pinky_proximal", "angle": 80},
            {"joint_id": "pinky_middle", "angle": 90},
            {"joint_id": "pinky_distal", "angle": 90},
        ]),
        ("展开动作", [
            {"joint_id": "thumb_proximal", "angle": 0},
            {"joint_id": "thumb_distal", "angle": 0},
            {"joint_id": "index_proximal", "angle": 0},
            {"joint_id": "index_middle", "angle": 0},
            {"joint_id": "index_distal", "angle": 0},
            {"joint_id": "middle_proximal", "angle": 0},
            {"joint_id": "middle_middle", "angle": 0},
            {"joint_id": "middle_distal", "angle": 0},
            {"joint_id": "ring_proximal", "angle": 0},
            {"joint_id": "ring_middle", "angle": 0},
            {"joint_id": "ring_distal", "angle": 0},
            {"joint_id": "pinky_proximal", "angle": 0},
            {"joint_id": "pinky_middle", "angle": 0},
            {"joint_id": "pinky_distal", "angle": 0},
        ]),
    ]

    all_passed = True
    for seq_name, joints in sequences:
        try:
            result = client.set_multi_joints(joints, duration_ms=800)
            print_result(seq_name, result)
            time.sleep(1)
        except Exception as e:
            print_result(seq_name, False, str(e))
            all_passed = False

    return all_passed


def main():
    parser = argparse.ArgumentParser(description="Aero Hand 连接测试")
    parser.add_argument("--host", default="192.168.4.1", help="ESP32 IP地址")
    parser.add_argument("--port", type=int, default=8765, help="WebSocket端口")
    parser.add_argument("--quick", action="store_true", help="快速测试(跳过全部关节遍历)")
    args = parser.parse_args()

    print("=" * 50)
    print("   Aero Hand WebSocket 连接测试")
    print("=" * 50)
    print(f"目标: {args.host}:{args.port}")
    print()

    # 创建客户端
    client = AeroWebSocketClient(args.host, args.port)

    # 运行测试
    results = []

    # 测试1: 连接
    results.append(("连接测试", test_connection(client)))

    if not results[-1][1]:
        print("\n[错误] 无法连接到服务器，测试终止")
        print("请检查:")
        print("  1. ESP32是否已烧录固件")
        print("  2. WiFi热点'AeroHand_WIFI'是否已连接")
        print("  3. IP地址是否正确")
        client.disconnect()
        sys.exit(1)

    # 测试2: 单关节
    results.append(("单关节测试", test_single_joint(client)))
    time.sleep(1)

    # 测试3: 多关节
    results.append(("多关节测试", test_multi_joint(client)))
    time.sleep(1)

    # 测试4: 归零
    results.append(("归零测试", test_homing(client)))
    time.sleep(1)

    # 测试5: 全部关节(可选)
    if not args.quick:
        results.append(("全关节遍历", test_all_joints(client)))
        time.sleep(1)

    # 测试6: 动作序列
    results.append(("动作序列", test_motion_sequences(client)))

    # 归零
    client.homing()

    # 断开连接
    client.disconnect()

    # 汇总
    print("\n" + "=" * 50)
    print("   测试结果汇总")
    print("=" * 50)

    passed = sum(1 for _, r in results if r)
    total = len(results)

    for name, result in results:
        status = "✓" if result else "✗"
        color = "\033[92m" if result else "\033[91m"
        print(f"  {color}{status}\033[0m {name}")

    print()
    print(f"通过: {passed}/{total}")

    if passed == total:
        print("\033[92m所有测试通过! ESP32固件工作正常\033[0m")
    else:
        print(f"\033[91m有 {total - passed} 项测试失败\033[0m")

    print()


if __name__ == "__main__":
    main()
