# Aero Hand WebSocket C DLL

C语言动态库，用于给C#/C++/Python等语言提供WebSocket通信能力。

## 构建

### Windows (Visual Studio Developer Command Prompt)

```batch
mkdir build
cd build
cmake ..
cmake --build . --config Release
```

### Linux/macOS

```bash
mkdir build
cd build
cmake ..
make
```

输出文件：
- Windows: `build/bin/aero_ws.dll`
- Linux: `build/lib/libaero_ws.so`
- macOS: `build/lib/libaero_ws.dylib`

## 使用方法

### C/C++

```c
#include "aero_ws.h"

// 创建连接
AeroWSHandle handle = aero_ws_create("192.168.1.100", 8765);

// 连接
int result = aero_ws_connect(handle, 5000);
if (result != AERO_WS_OK) {
    printf("Error: %s\n", aero_ws_get_error());
    return;
}

// 控制关节
aero_ws_set_joint(handle, "index_proximal", 45.0f, 500);

// 归零
aero_ws_homing(handle);

// 断开
aero_ws_disconnect(handle);
aero_ws_destroy(handle);
```

### C#

```csharp
using System.Runtime.InteropServices;

[DllImport("aero_ws.dll")]
public static extern IntPtr aero_ws_create(string host, int port);

[DllImport("aero_ws.dll")]
public static extern int aero_ws_connect(IntPtr handle, int timeout_ms);

// ... 其他函数声明类似

class Program
{
    static void Main()
    {
        IntPtr handle = aero_ws_create("192.168.1.100", 8765);
        aero_ws_connect(handle, 5000);
        aero_ws_set_joint(handle, "index_proximal", 45.0f, 500);
        aero_ws_homing(handle);
        aero_ws_disconnect(handle);
        aero_ws_destroy(handle);
    }
}
```

### Python (ctypes)

```python
from ctypes import CDLL, c_char_p, c_void_p, c_int, c_float

dll = CDLL("aero_ws.dll")

# 设置函数签名...
handle = dll.aero_ws_create(b"192.168.1.100", 8765)
dll.aero_ws_connect(handle, 5000)
dll.aero_ws_set_joint(handle, b"index_proximal", 45.0, 500)
dll.aero_ws_homing(handle)
dll.aero_ws_disconnect(handle)
dll.aero_ws_destroy(handle)
```

详见 `examples/` 目录下的示例代码。

## API列表

| 函数 | 说明 |
|------|------|
| `aero_ws_version()` | 获取DLL版本 |
| `aero_ws_get_error()` | 获取错误信息 |
| `aero_ws_create(host, port)` | 创建连接句柄 |
| `aero_ws_destroy(handle)` | 销毁句柄 |
| `aero_ws_connect(handle, timeout)` | 连接到服务器 |
| `aero_ws_disconnect(handle)` | 断开连接 |
| `aero_ws_is_connected(handle)` | 检查连接状态 |
| `aero_ws_set_joint(handle, joint_id, angle, duration)` | 控制单关节 |
| `aero_ws_set_joints(handle, joints[], count, duration)` | 控制多关节 |
| `aero_ws_get_states(handle, states, max_count)` | 获取关节状态 |
| `aero_ws_homing(handle)` | 归零 |
| `aero_ws_send_raw(handle, json)` | 发送原始JSON |

## 错误码

| 错误码 | 说明 |
|--------|------|
| AERO_WS_OK (0) | 成功 |
| AERO_WS_ERROR (-1) | 一般错误 |
| AERO_WS_NOT_CONNECTED (-2) | 未连接 |
| AERO_WS_TIMEOUT (-3) | 连接超时 |
| AERO_WS_INVALID_PARAM (-4) | 参数无效 |
| AERO_WS_SEND_ERROR (-5) | 发送失败 |
| AERO_WS_RECV_ERROR (-6) | 接收失败 |
