/**
 * Aero Hand WebSocket DLL - C# 调用示例
 *
 * 编译: csc aero_ws_example.cs
 * 运行: aero_ws_example.exe
 */

using System;
using System.Runtime.InteropServices;
using System.Threading;

public class AeroWSExample
{
    // DLL导入
    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr aero_ws_version();

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr aero_ws_get_error();

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr aero_ws_create([MarshalAs(UnmanagedType.LPStr)] string host, int port);

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern void aero_ws_destroy(IntPtr handle);

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int aero_ws_connect(IntPtr handle, int timeout_ms);

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern void aero_ws_disconnect(IntPtr handle);

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int aero_ws_is_connected(IntPtr handle);

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int aero_ws_set_joint(IntPtr handle, [MarshalAs(UnmanagedType.LPStr)] string joint_id, float angle, int duration_ms);

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int aero_ws_send_raw(IntPtr handle, [MarshalAs(UnmanagedType.LPStr)] string json);

    [DllImport("aero_ws.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int aero_ws_homing(IntPtr handle);

    public static void Main(string[] args)
    {
        Console.WriteLine("=== Aero Hand WebSocket DLL C# Test ===\n");

        // 获取版本
        Console.WriteLine($"DLL Version: {Marshal.PtrToStringAnsi(aero_ws_version())}");

        // 创建连接
        Console.WriteLine("\nCreating connection...");
        IntPtr handle = aero_ws_create("192.168.1.100", 8765);

        if (handle == IntPtr.Zero)
        {
            Console.WriteLine($"Create failed: {Marshal.PtrToStringAnsi(aero_ws_get_error())}");
            return;
        }

        try
        {
            // 连接
            Console.WriteLine("Connecting...");
            int result = aero_ws_connect(handle, 5000);

            if (result != 0)
            {
                Console.WriteLine($"Connect failed: {Marshal.PtrToStringAnsi(aero_ws_get_error())}");
                return;
            }

            Console.WriteLine("Connected!");

            // 测试控制
            Console.WriteLine("\nSending test commands...");

            // 单关节控制
            result = aero_ws_set_joint(handle, "index_proximal", 45.0f, 500);
            if (result == 0)
            {
                Console.WriteLine("Set joint OK");
            }
            Thread.Sleep(1000);

            result = aero_ws_set_joint(handle, "index_middle", 30.0f, 500);
            Thread.Sleep(1000);

            // 归零
            result = aero_ws_homing(handle);
            if (result == 0)
            {
                Console.WriteLine("Homing OK");
            }

            Console.WriteLine("\nTest complete!");
        }
        catch (Exception e)
        {
            Console.WriteLine($"Error: {e.Message}");
        }
        finally
        {
            // 清理
            aero_ws_disconnect(handle);
            aero_ws_destroy(handle);
            Console.WriteLine("\nCleanup done");
        }
    }
}
