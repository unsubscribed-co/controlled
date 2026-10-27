# Replays app/build/keymap-<KLID>.tsv (written by PcTest) through Windows' own layout and reports any character whose
# key presses produce something else. Run on a Windows PC after :app:testProdDebugUnitTest, once per layout:
#   0000080A Spanish (Latin America), 0000040A Spanish (Spain), 00000409 US.
param([string]$Layout = '0000080A')
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Kb {
    [DllImport("user32.dll")] public static extern IntPtr LoadKeyboardLayout(string id, uint flags);
    [DllImport("user32.dll")] public static extern uint MapVirtualKeyEx(uint code, uint type, IntPtr hkl);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int ToUnicodeEx(uint vk, uint scan, byte[] state, StringBuilder buf, int len, uint flags, IntPtr hkl);
}
'@
$hkl = [Kb]::LoadKeyboardLayout($Layout, 0x80)
# HID keyboard usage -> PC set-1 scan code, for the keys Keymap uses.
$scan = @{}
$letters = 0x1E,0x30,0x2E,0x20,0x12,0x21,0x22,0x23,0x17,0x24,0x25,0x26,0x32,0x31,0x18,0x19,0x10,0x13,0x1F,0x14,0x16,0x2F,0x11,0x2D,0x15,0x2C
for ($i = 0; $i -lt 26; $i++) { $scan[0x04 + $i] = $letters[$i] }
for ($i = 0; $i -lt 10; $i++) { $scan[0x1E + $i] = 0x02 + $i }
$scan[0x28] = 0x1C; $scan[0x2B] = 0x0F; $scan[0x2C] = 0x39
$scan[0x2D] = 0x0C; $scan[0x2E] = 0x0D; $scan[0x2F] = 0x1A; $scan[0x30] = 0x1B; $scan[0x31] = 0x2B
$scan[0x33] = 0x27; $scan[0x34] = 0x28; $scan[0x35] = 0x29; $scan[0x36] = 0x33; $scan[0x37] = 0x34; $scan[0x38] = 0x35; $scan[0x64] = 0x56

function Press([int]$mods, [int]$usage) {
    $state = New-Object byte[] 256
    if ($mods -band 0x02) { $state[0x10] = 0x80; $state[0xA0] = 0x80 }
    if ($mods -band 0x40) { $state[0x11] = 0x80; $state[0x12] = 0x80; $state[0xA2] = 0x80; $state[0xA5] = 0x80 }
    $sc = [uint32]$scan[$usage]
    $vk = [Kb]::MapVirtualKeyEx($sc, 3, $hkl)
    $buf = New-Object System.Text.StringBuilder 8
    $n = [Kb]::ToUnicodeEx($vk, $sc, $state, $buf, 8, 0, $hkl)
    if ($n -lt 0) { return '' }  # a dead key: it waits for the next press
    return $buf.ToString().Substring(0, [Math]::Max($n, 0))
}

$bad = 0
foreach ($line in Get-Content (Join-Path $PSScriptRoot "..\app\build\keymap-$Layout.tsv") -Encoding UTF8) {
    $code, $strokes = $line -split "`t"
    $want = [string][char][int]$code
    $got = ''
    foreach ($s in $strokes -split ' ') { $m, $u = $s -split ':'; $got += Press ([int]$m) ([int]$u) }
    if ($want -eq "`n") { $want = "`r" }
    if ($got -ne $want) { $bad++; Write-Output ("MISMATCH U+{0:X4} want [{1}] got [{2}] via {3}" -f [int]$code, $want, $got, $strokes) }
}
Write-Output "$Layout checked; mismatches: $bad"
