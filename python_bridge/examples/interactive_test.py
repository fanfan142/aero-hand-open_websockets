#!/usr/bin/env python3
"""
Aero Hand 交互式测试工具
Aero Hand Interactive Test Tool

用法:
    python interactive_test.py --host 192.168.4.1 --port 8765

交互命令:
    help     - 显示帮助
    list     - 列出所有关节
    get <id> - 获取关节状态
    set <id> <angle> [ms] - 设置关节角度
    multi    - 进入多关节控制模式
    home     - 归零所有关节
    test     - 运行自动测试序列
    quit     - 退出

示例:
    > list
    > set index_proximal 45
    > set thumb_rotation -15
    > multi
    multi> index_proximal 30
    multi> index_middle 20
    multi> send
    > test
    > quit
"""

import argparse
import cmd
import sys
import time
import os

# 添加父目录到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from aero_ws_python import AeroWebSocketClient
from aero_ws_python.protocol import JOINT_TO_SERVO_ID, JOINT_ANGLE_LIMITS, get_joint_type, get_angle_limits


class AeroHandTestShell(cmd.Cmd):
    """交互式测试Shell"""

    intro = """
╔══════════════════════════════════════════════════════════════╗
║           Aero Hand WebSocket 交互式测试工具                  ║
║           Aero Hand Interactive Test Tool                     ║
╠══════════════════════════════════════════════════════════════╣
║  命令:                                                       ║
║    help     - 显示帮助           list    - 列出所有关节       ║
║    set      - 设置关节角度       get     - 获取关节状态       ║
║    multi    - 多关节控制模式     home    - 归零               ║
║    test     - 自动测试序列       quit    - 退出              ║
╚══════════════════════════════════════════════════════════════╝
"""

    prompt = 'aero> '
    doc_header = '可用命令 (help <command> 查看详细帮助)'

    def __init__(self, client: AeroWebSocketClient):
        super().__init__()
        self.client = client
        self.multi_joints = []  # 多关节控制队列
        self.multi_mode = False

    # ==================== 连接管理 ====================

    def do_connect(self, arg):
        """connect - 重新连接到服务器"""
        if self.client.is_connected():
            print("Already connected")
            return
        if self.client.connect():
            print(f"Connected to {self.client.host}:{self.client.port}")
        else:
            print("Connection failed")

    def do_status(self, arg):
        """status - 显示连接状态"""
        if self.client.is_connected():
            print(f"✓ Connected to {self.client.host}:{self.client.port}")
        else:
            print("✗ Disconnected")

    # ==================== 关节操作 ====================

    def do_list(self, arg):
        """list - 列出所有关节及其角度范围"""
        print("\n{:<20} {:>10} {:>10}".format("关节ID", "最小", "最大"))
        print("-" * 45)
        for joint_id in JOINT_TO_SERVO_ID.keys():
            min_a, max_a = get_angle_limits(joint_id)
            servo_id = JOINT_TO_SERVO_ID[joint_id]
            print(f"{joint_id:<20} {min_a:>6}°  {max_a:>6}°  (servo #{servo_id})")
        print()

    def do_set(self, arg):
        """set <joint_id> <angle> [duration_ms] - 设置单个关节角度"""
        args = arg.split()
        if len(args) < 2:
            print("用法: set <joint_id> <angle> [duration_ms]")
            print("示例: set index_proximal 45")
            print("示例: set thumb_rotation -15 1000")
            return

        joint_id = args[0]
        try:
            angle = float(args[1])
        except ValueError:
            print(f"错误: 无效的角度值 '{args[1]}'")
            return

        duration = 500
        if len(args) >= 3:
            try:
                duration = int(args[2])
            except ValueError:
                print(f"错误: 无效的持续时间 '{args[2]}'")
                return

        # 验证关节ID
        if joint_id not in JOINT_TO_SERVO_ID:
            print(f"错误: 未知的关节ID '{joint_id}'")
            print("使用 'list' 命令查看所有有效的关节ID")
            return

        # 验证角度范围
        min_a, max_a = get_angle_limits(joint_id)
        if not (min_a <= angle <= max_a):
            print(f"错误: 角度 {angle}° 超出范围 [{min_a}, {max_a}]")
            return

        # 发送指令
        if self.client.is_connected():
            self.client.set_joint(joint_id, angle, duration)
            print(f"✓ 已发送: {joint_id} -> {angle}° (持续 {duration}ms)")
        else:
            print("错误: 未连接到服务器")
            print(f"  [模拟] {joint_id} -> {angle}° (未发送)")

    def do_get(self, arg):
        """get [joint_id] - 获取关节状态(简化实现)"""
        if arg.strip():
            joint_id = arg.strip()
            if joint_id not in JOINT_TO_SERVO_ID:
                print(f"错误: 未知的关节ID '{joint_id}'")
                return
            print(f"{joint_id}: (状态查询需要服务器支持)")
        else:
            print("所有关节状态: (状态查询需要服务器支持)")

    def do_home(self, arg):
        """home - 所有关节归零"""
        if self.client.is_connected():
            self.client.homing()
            print("✓ 已发送归零指令")
        else:
            print("错误: 未连接到服务器")
            print("  [模拟] 所有关节 -> 0° (未发送)")

    # ==================== 多关节控制 ====================

    def do_multi(self, arg):
        """multi - 进入多关节控制模式"""
        self.multi_joints = []
        self.multi_mode = True
        self.prompt = 'multi> '
        print("多关节控制模式")
        print("  添加关节: <joint_id> <angle>")
        print("  发送指令: send")
        print("  取消并退出: cancel")
        print("  示例: index_proximal 45")

    def do_send(self, arg):
        """send - 发送多关节指令"""
        if not self.multi_joints:
            print("没有待发送的关节")
            return

        if self.client.is_connected():
            self.client.set_multi_joints(self.multi_joints, duration_ms=500)
            print(f"✓ 已发送 {len(self.multi_joints)} 个关节指令")
            for j in self.multi_joints:
                print(f"    {j['joint_id']}: {j['angle']}°")
        else:
            print("错误: 未连接到服务器")
            print(f"  [模拟] 发送 {len(self.multi_joints)} 个关节:")
            for j in self.multi_joints:
                print(f"    {j['joint_id']}: {j['angle']}°")

        self.multi_joints = []

    def do_cancel(self, arg):
        """cancel - 取消多关节指令并退出多模式"""
        self.multi_joints = []
        self.multi_mode = False
        self.prompt = 'aero> '
        print("已取消")

    def do_exit(self, arg):
        """exit - 退出多关节模式(不取消已添加的关节)"""
        self.multi_mode = False
        self.prompt = 'aero> '
        print(f"待发送 {len(self.multi_joints)} 个关节指令已保留")

    def default(self, line):
        """处理多关节模式下的输入"""
        if self.multi_mode:
            args = line.split()
            if len(args) < 2:
                print("用法: <joint_id> <angle>")
                return

            joint_id = args[0]
            try:
                angle = float(args[1])
            except ValueError:
                print(f"错误: 无效的角度值 '{args[1]}'")
                return

            if joint_id not in JOINT_TO_SERVO_ID:
                print(f"错误: 未知的关节ID '{joint_id}'")
                return

            min_a, max_a = get_angle_limits(joint_id)
            if not (min_a <= angle <= max_a):
                print(f"错误: 角度 {angle}° 超出范围 [{min_a}, {max_a}]")
                return

            self.multi_joints.append({"joint_id": joint_id, "angle": angle})
            print(f"  已添加: {joint_id} -> {angle}°")
            return

        print(f"未知命令: {line}")
        print("输入 'help' 查看可用命令")

    # ==================== 自动测试 ====================

    def do_test(self, arg):
        """test - 运行自动测试序列"""
        print("\n" + "=" * 50)
        print("开始自动测试序列...")
        print("=" * 50)

        # 测试序列
        test_sequence = [
            ("归零", lambda: self.client.homing() if self.client.is_connected() else None),
            ("拇指-近端 45°", lambda: self.client.set_joint("thumb_proximal", 45, 500) if self.client.is_connected() else None),
            ("拇指-远端 30°", lambda: self.client.set_joint("thumb_distal", 30, 500) if self.client.is_connected() else None),
            ("食指-近端 60°", lambda: self.client.set_joint("index_proximal", 60, 500) if self.client.is_connected() else None),
            ("食指-中端 45°", lambda: self.client.set_joint("index_middle", 45, 500) if self.client.is_connected() else None),
            ("食指-远端 30°", lambda: self.client.set_joint("index_distal", 30, 500) if self.client.is_connected() else None),
            ("4指弯曲", lambda: self.client.set_multi_joints([
                {"joint_id": "index_proximal", "angle": 60},
                {"joint_id": "middle_proximal", "angle": 60},
                {"joint_id": "ring_proximal", "angle": 60},
                {"joint_id": "pinky_proximal", "angle": 60},
            ], 500) if self.client.is_connected() else None),
            ("拇指旋转 15°", lambda: self.client.set_joint("thumb_rotation", 15, 500) if self.client.is_connected() else None),
            ("拇指旋转 -15°", lambda: self.client.set_joint("thumb_rotation", -15, 500) if self.client.is_connected() else None),
            ("归零", lambda: self.client.homing() if self.client.is_connected() else None),
            ("抓取动作", lambda: self.client.set_multi_joints([
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
            ], 1000) if self.client.is_connected() else None),
            ("全部归零", lambda: self.client.homing() if self.client.is_connected() else None),
        ]

        for i, (desc, action) in enumerate(test_sequence, 1):
            print(f"\n[{i}/{len(test_sequence)}] {desc}...", end=" ")
            if self.client.is_connected():
                action()
                print("✓")
            else:
                print("⏭ (未连接)")
            time.sleep(0.8)

        print("\n" + "=" * 50)
        print("自动测试完成!")
        print("=" * 50)

    # ==================== 快捷命令 ====================

    def do_wave(self, arg):
        """wave - 挥手动作"""
        print("执行挥手动作...")
        if not self.client.is_connected():
            print("错误: 未连接到服务器")
            return

        for i in range(3):
            self.client.set_multi_joints([
                {"joint_id": "index_proximal", "angle": 60},
                {"joint_id": "middle_proximal", "angle": 40},
                {"joint_id": "ring_proximal", "angle": 20},
                {"joint_id": "pinky_proximal", "angle": 0},
            ], 200)
            time.sleep(0.3)
            self.client.set_multi_joints([
                {"joint_id": "index_proximal", "angle": 30},
                {"joint_id": "middle_proximal", "angle": 60},
                {"joint_id": "ring_proximal", "angle": 60},
                {"joint_id": "pinky_proximal", "angle": 60},
            ], 200)
            time.sleep(0.3)
        print("完成!")

    def do_ok(self, arg):
        """ok - 比OK手势"""
        print("执行OK手势...")
        if not self.client.is_connected():
            print("错误: 未连接到服务器")
            return

        self.client.set_multi_joints([
            {"joint_id": "thumb_proximal", "angle": 30},
            {"joint_id": "thumb_distal", "angle": 50},
            {"joint_id": "index_proximal", "angle": 80},
            {"joint_id": "index_middle", "angle": 90},
            {"joint_id": "index_distal", "angle": 90},
            {"joint_id": "middle_proximal", "angle": 0},
            {"joint_id": "middle_middle", "angle": 0},
            {"joint_id": "middle_distal", "angle": 0},
            {"joint_id": "ring_proximal", "angle": 0},
            {"joint_id": "ring_middle", "angle": 0},
            {"joint_id": "ring_distal", "angle": 0},
            {"joint_id": "pinky_proximal", "angle": 0},
            {"joint_id": "pinky_middle", "angle": 0},
            {"joint_id": "pinky_distal", "angle": 0},
        ], 500)
        print("完成!")

    def do_grab(self, arg):
        """grab - 抓取动作"""
        print("执行抓取动作...")
        if not self.client.is_connected():
            print("错误: 未连接到服务器")
            return

        self.client.set_multi_joints([
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
        ], 1000)
        print("完成!")

    def do_point(self, arg):
        """point - 指向动作"""
        print("执行指向动作...")
        if not self.client.is_connected():
            print("错误: 未连接到服务器")
            return

        self.client.set_multi_joints([
            {"joint_id": "thumb_proximal", "angle": 20},
            {"joint_id": "thumb_distal", "angle": 30},
            {"joint_id": "index_proximal", "angle": 0},
            {"joint_id": "index_middle", "angle": 0},
            {"joint_id": "index_distal", "angle": 0},
            {"joint_id": "middle_proximal", "angle": 60},
            {"joint_id": "middle_middle", "angle": 90},
            {"joint_id": "middle_distal", "angle": 90},
            {"joint_id": "ring_proximal", "angle": 60},
            {"joint_id": "ring_middle", "angle": 90},
            {"joint_id": "ring_distal", "angle": 90},
            {"joint_id": "pinky_proximal", "angle": 60},
            {"joint_id": "pinky_middle", "angle": 90},
            {"joint_id": "pinky_distal", "angle": 90},
        ], 500)
        print("完成!")

    # ==================== 退出 ====================

    def do_quit(self, arg):
        """quit - 退出程序"""
        print("\n正在断开连接...")
        self.client.disconnect()
        print("再见!")
        return True

    def do_exit(self, arg):
        """exit - 退出程序 (quit的别名)"""
        return self.do_quit(arg)

    # ==================== 别名 ====================

    def do_q(self, arg):
        """q - quit的别名"""
        return self.do_quit(arg)

    def do_l(self, arg):
        """l - list的别名"""
        return self.do_list(arg)

    def do_s(self, arg):
        """s - set的别名"""
        return self.do_set(arg)

    def do_h(self, arg):
        """h - home的别名"""
        return self.do_home(arg)

    def do_t(self, arg):
        """t - test的别名"""
        return self.do_test(arg)


def main():
    parser = argparse.ArgumentParser(
        description="Aero Hand WebSocket 交互式测试工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python interactive_test.py --host 192.168.4.1 --port 8765
  python interactive_test.py --host localhost --fake

交互命令:
  help     - 显示帮助
  list     - 列出所有关节
  set      - 设置关节角度 (set index_proximal 45)
  multi    - 多关节控制模式
  home     - 归零
  test     - 自动测试序列
  wave     - 挥手动作
  grab     - 抓取动作
  point    - 指向动作
  quit     - 退出
"""
    )
    parser.add_argument("--host", default="192.168.4.1", help="服务器地址 (默认: 192.168.4.1)")
    parser.add_argument("--port", type=int, default=8765, help="端口 (默认: 8765)")
    args = parser.parse_args()

    # 创建客户端
    client = AeroWebSocketClient(args.host, args.port)

    # 连接
    print(f"正在连接到 {args.host}:{args.port}...")
    if not client.connect():
        print("连接失败! 将以模拟模式运行")
        print("输入 'help' 查看可用命令")
    else:
        print("连接成功!")

    # 启动交互Shell
    shell = AeroHandTestShell(client)
    try:
        shell.cmdloop()
    except KeyboardInterrupt:
        print("\n\n收到中断信号，正在退出...")
        client.disconnect()


if __name__ == "__main__":
    main()
