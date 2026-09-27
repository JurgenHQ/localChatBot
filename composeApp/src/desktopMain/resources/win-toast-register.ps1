# Registra un acceso directo en el menu inicio con la propiedad AppUserModelID, para
# que Windows atribuya los toasts (Windows.UI.Notifications) al nombre e icono de la app
# en lugar de mostrar el AUMID crudo. Es el metodo estandar para apps de escritorio sin
# paquete MSIX (el DisplayName del registro no lo honra Windows 10/11 de forma fiable).
#
# Parametros: -AumId <id> -LinkName <nombre> -Target <exe> -IconPath <.ico>
param(
  [Parameter(Mandatory = $true)] [string]$AumId,
  [Parameter(Mandatory = $true)] [string]$LinkName,
  [Parameter(Mandatory = $true)] [string]$Target,
  [Parameter(Mandatory = $true)] [string]$IconPath
)
$ErrorActionPreference = 'Stop'

# --- COM interop: IShellLink + IPropertyStore para crear el .lnk con AppUserModelID ---
$cs = @'
using System;
using System.Runtime.InteropServices;

namespace ToastReg {
  [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
  internal class CShellLink { }

  [ComImport, Guid("000214F9-0000-0000-C000-000000000046"),
   InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IShellLinkW {
    void GetPath([MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder f, int cch, IntPtr pfd, uint flags);
    void GetIDList(out IntPtr ppidl);
    void SetIDList(IntPtr pidl);
    void GetDescription([MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder n, int cch);
    void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string n);
    void GetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder d, int cch);
    void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string d);
    void GetArguments([MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder a, int cch);
    void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string a);
    void GetHotkey(out short w);
    void SetHotkey(short w);
    void GetShowCmd(out int c);
    void SetShowCmd(int c);
    void GetIconLocation([MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder i, int cch, out int idx);
    void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string i, int idx);
    void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string r, uint res);
    void Resolve(IntPtr hwnd, uint flags);
    void SetPath([MarshalAs(UnmanagedType.LPWStr)] string f);
  }

  [ComImport, Guid("0000010b-0000-0000-C000-000000000046"),
   InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IPersistFile {
    void GetClassID(out Guid id);
    [PreserveSig] int IsDirty();
    void Load([MarshalAs(UnmanagedType.LPWStr)] string f, uint mode);
    void Save([MarshalAs(UnmanagedType.LPWStr)] string f, [MarshalAs(UnmanagedType.Bool)] bool remember);
    void SaveCompleted([MarshalAs(UnmanagedType.LPWStr)] string f);
    void GetCurFile([MarshalAs(UnmanagedType.LPWStr)] out string f);
  }

  [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"),
   InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IPropertyStore {
    void GetCount(out uint c);
    void GetAt(uint i, out PropertyKey k);
    void GetValue(ref PropertyKey k, out PropVariant v);
    void SetValue(ref PropertyKey k, [In] PropVariant v);
    void Commit();
  }

  [StructLayout(LayoutKind.Sequential, Pack = 4)]
  internal struct PropertyKey {
    public Guid fmtid;
    public uint pid;
    public PropertyKey(Guid g, uint p) { fmtid = g; pid = p; }
  }

  [StructLayout(LayoutKind.Sequential)]
  internal sealed class PropVariant : IDisposable {
    ushort vt;
    ushort r1; ushort r2; ushort r3;
    IntPtr p;
    IntPtr p2;
    public PropVariant(string value) {
      vt = 31; // VT_LPWSTR
      p = Marshal.StringToCoTaskMemUni(value);
    }
    public void Dispose() {
      if (p != IntPtr.Zero) { Marshal.FreeCoTaskMem(p); p = IntPtr.Zero; }
    }
  }

  public static class Installer {
    public static void Install(string linkPath, string aumId, string target, string iconPath) {
      var link = (IShellLinkW)new CShellLink();
      link.SetPath(target);
      if (!string.IsNullOrEmpty(iconPath) && System.IO.File.Exists(iconPath))
        link.SetIconLocation(iconPath, 0);
      var store = (IPropertyStore)link;
      var key = new PropertyKey(new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), 5); // PKEY_AppUserModel_ID
      using (var pv = new PropVariant(aumId)) {
        store.SetValue(ref key, pv);
        store.Commit();
      }
      ((IPersistFile)link).Save(linkPath, true);
    }
  }
}
'@
Add-Type -TypeDefinition $cs -Language CSharp | Out-Null

$startMenu = [Environment]::GetFolderPath('Programs')
$linkPath  = Join-Path $startMenu ("$LinkName.lnk")
[ToastReg.Installer]::Install($linkPath, $AumId, $Target, $IconPath)

# Avisar al shell del nuevo acceso directo para que indexe el mapeo AUMID -> nombre/icono
# (si no, el primer toast podria salir con el AUMID crudo hasta que el shell lo procese).
$sig = '[DllImport("shell32.dll")] public static extern void SHChangeNotify(int e, uint f, IntPtr a, IntPtr b);'
$shc = Add-Type -MemberDefinition $sig -Name Notify -Namespace ShellApi -PassThru
$shc::SHChangeNotify(0x08000000, 0, [IntPtr]::Zero, [IntPtr]::Zero) # SHCNE_ASSOCCHANGED

Write-Output "OK: $linkPath"
