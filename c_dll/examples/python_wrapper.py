"""
Aero Hand WebSocket DLL - Python Wrapper
Python ctypes 调用示例

用法:
    python python_wrapper.py
"""

import ctypes
import time
import json
from ctypes import c_char_p, c_void_p, c_int, c_float, byref, POINTER


class AeroWSDLL:
    """Python ctypes包装器"""

    def __init__(self, dll_path=None):
        if dll_path is None:
            # 默认路径
            import os
            if os.name == 'nt':
                dll_path = "aero_ws.dll"
            else:
                dll_path = "libaero_ws.so"

        self.dll = ctypes.CDLL(dll_path)

        # 设置函数签名
        self._setup_functions()

    def _setup_functions(self):
        dll = self.dll

        # aero_ws_version
        dll.aero_ws_version.restype = c_char_p

        # aero_ws_get_error
        dll.aero_ws_get_error.restype = c_char_p

        # aero_ws_create
        dll.aero_ws_create.argtypes = [c_char_p, c_int]
        dll.aero_ws_create.restype = c_void_p

        # aero_ws_destroy
        dll.aero_ws_destroy.argtypes = [c_void_p]

        # aero_ws_connect
        dll.aero_ws_connect.argtypes = [c_void_p, c_int]
        dll.aero_ws_connect.restype = c_int

        # aero_ws_disconnect
        dll.aero_ws_disconnect.argtypes = [c_void_p]

        # aero_ws_is_connected
        dll.aero_ws_is_connected.argtypes = [c_void_p]
        dll.aero_ws_is_connected.restype = c_int

        # aero_ws_set_joint
        dll.aero_ws_set_joint.argtypes = [c_void_p, c_char_p, c_float, c_int]
        dll.aero_ws_set_joint.restype = c_int

        # aero_ws_set_joints
        # 简化版：使用JSON字符串
        dll.aero_ws_send_raw.argtypes = [c_void_p, c_char_p]
        dll.aero_ws_send_raw.restype = c_int

        # aero_ws_homing
        dll.aero_ws_homing.argtypes = [c_void_p]
        dll.aero_ws_homing.restype = c_int

    def version(self):
        return self.dll.aero_ws_version().decode('utf-8')

    def get_error(self):
        return self.dll.aero_ws_get_error().decode('utf-8')

    def create(self, host, port):
        handle = self.dll.aero_ws_create(host.encode('utf-8'), port)
        if handle is None:
            raise Exception(f"Create failed: {self.get_error()}")
        return handle

    def destroy(self, handle):
        self.dll.aero_ws_destroy(handle)

    def connect(self, handle, timeout_ms=5000):
        result = self.dll.aero_ws_connect(handle, timeout_ms)
        if result != 0:
            raise Exception(f"Connect failed: {self.get_error()}")
        return True

    def disconnect(self, handle):
        self.dll.aero_ws_disconnect(handle)

    def is_connected(self, handle):
        return self.dll.aero_ws_is_connected(handle) == 1

    def set_joint(self, handle, joint_id, angle, duration_ms=500):
        result = self.dll.aero_ws_set_joint(
            handle,
            joint_id.encode('utf-8'),
            c_float(angle),
            duration_ms
        )
        if result != 0:
            raise Exception(f"Set joint failed: {self.get_error()}")
        return True

    def send_raw(self, handle, json_str):
        result = self.dll.aero_ws_send_raw(
            handle,
            json_str.encode('utf-8')
        )
        if result != 0:
            raise Exception(f"Send raw failed: {self.get_error()}")
        return True

    def homing(self, handle):
        result = self.dll.aero_ws_homing(handle)
        if result != 0:
            raise Exception(f"Homing failed: {self.get_error()}")
        return True


def main():
    print("=== Aero Hand WebSocket DLL Python Test ===\n")

    # 加载DLL
    try:
        aero = AeroWSDLL()
        print(f"DLL Version: {aero.version()}")
    except Exception as e:
        print(f"Failed to load DLL: {e}")
        print("\nNote: Compile the DLL first using CMake")
        print("  mkdir build && cd build")
        print("  cmake ..")
        print("  cmake --build .")
        return

    # 创建连接
    print("\nCreating connection...")
    handle = aero.create("192.168.1.100", 8765)

    # 连接（假设服务端在运行）
    print("Attempting to connect...")
    try:
        aero.connect(handle, timeout_ms=3000)
        print("Connected!")

        # 测试指令
        print("\nSending test commands...")

        aero.set_joint(handle, "index_proximal", 45.0, duration_ms=500)
        time.sleep(1)

        aero.set_joint(handle, "index_proximal", 0.0, duration_ms=500)
        time.sleep(1)

        aero.homing(handle)

        print("Test complete!")

    except Exception as e:
        print(f"Error: {e}")

    finally:
        # 清理
        aero.disconnect(handle)
        aero.destroy(handle)
        print("\nCleanup done")


if __name__ == "__main__":
    main()
