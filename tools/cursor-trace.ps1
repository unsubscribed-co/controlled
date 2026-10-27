# Measures how smoothly the PC's pointer moves: samples its position about every millisecond for -Seconds and prints
# how often it moved and the gaps while moving. Swipe on the phone's trackpad by hand, or script it:
#   adb shell input swipe 300 900 900 900 1500
# Gaps over 150 ms count as the finger lifting, not stutter. A Bluetooth mouse updates every 8 to 15 ms; gaps over
# 30 ms read as stutter.
param([double]$Seconds = 4)
Add-Type -AssemblyName System.Windows.Forms
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$last = [System.Windows.Forms.Cursor]::Position
$changes = New-Object System.Collections.Generic.List[double]
while ($watch.Elapsed.TotalSeconds -lt $Seconds) {
    $p = [System.Windows.Forms.Cursor]::Position
    if ($p -ne $last) { $changes.Add($watch.Elapsed.TotalMilliseconds); $last = $p }
}
$gaps = @(); for ($i = 1; $i -lt $changes.Count; $i++) { $g = $changes[$i] - $changes[$i - 1]; if ($g -le 150) { $gaps += $g } }
if ($gaps.Count -lt 3) { Write-Output "the pointer barely moved ($($changes.Count) updates)"; exit }
$sorted = $gaps | Sort-Object
$moving = ($gaps | Measure-Object -Sum).Sum
"{0} updates over {1:N0} ms of movement: {2:N0} per second" -f $gaps.Count, $moving, ($gaps.Count / $moving * 1000)
"gap between updates: median {0:N1} ms, p95 {1:N1} ms, max {2:N1} ms" -f $sorted[[int]($sorted.Count / 2)], $sorted[[Math]::Min($sorted.Count - 1, [int]($sorted.Count * 0.95))], $sorted[-1]
"stutters (30 to 150 ms): {0}, that's {1:N1} per second of movement" -f @($gaps | Where-Object { $_ -gt 30 }).Count, (@($gaps | Where-Object { $_ -gt 30 }).Count / $moving * 1000)
