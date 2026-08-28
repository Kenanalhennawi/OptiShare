Add-Type -AssemblyName System.Windows.Forms

$ErrorActionPreference = 'Stop'
$DiscoveryPort = 49891
$TransferPort = 49890
$Magic = [Text.Encoding]::ASCII.GetBytes("OPTISHARE-PC-1`n")
$DownloadRoot = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\OptiShare'
$TokenBytes = New-Object byte[] 24
$Rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $Rng.GetBytes($TokenBytes) } finally { $Rng.Dispose() }
$SessionToken = ([BitConverter]::ToString($TokenBytes)).Replace('-','').ToLowerInvariant()
$ComputerName = $env:COMPUTERNAME
if ([string]::IsNullOrWhiteSpace($ComputerName)) { $ComputerName = 'Windows-PC' }

function Read-Exact([IO.Stream]$Stream, [int]$Count) {
    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) { throw 'Connection ended unexpectedly.' }
        $offset += $read
    }
    return $buffer
}

function Bytes-Equal([byte[]]$A, [byte[]]$B) {
    if ($null -eq $A -or $null -eq $B -or $A.Length -ne $B.Length) { return $false }
    $diff = 0
    for ($i=0; $i -lt $A.Length; $i++) { $diff = $diff -bor ($A[$i] -bxor $B[$i]) }
    return $diff -eq 0
}

function Read-Int32BE([IO.Stream]$Stream) {
    $b = Read-Exact $Stream 4
    return [int](($b[0] -shl 24) -bor ($b[1] -shl 16) -bor ($b[2] -shl 8) -bor $b[3])
}

function Read-Int64BE([IO.Stream]$Stream) {
    $b = Read-Exact $Stream 8
    [UInt64]$value = 0
    for ($i = 0; $i -lt 8; $i++) { $value = ($value -shl 8) -bor [UInt64]$b[$i] }
    if ($value -gt [Int64]::MaxValue) { throw 'Invalid file size.' }
    return [Int64]$value
}

function Read-Utf8([IO.Stream]$Stream, [int]$MaxBytes) {
    $length = Read-Int32BE $Stream
    if ($length -lt 0 -or $length -gt $MaxBytes) { throw 'Invalid metadata length.' }
    if ($length -eq 0) { return '' }
    return [Text.Encoding]::UTF8.GetString((Read-Exact $Stream $length))
}

function Safe-Component([string]$Value, [string]$Fallback) {
    if ([string]::IsNullOrWhiteSpace($Value)) { $Value = $Fallback }
    foreach ($c in [IO.Path]::GetInvalidFileNameChars()) { $Value = $Value.Replace([string]$c, '_') }
    $Value = $Value.Trim().TrimEnd('.')
    if ([string]::IsNullOrWhiteSpace($Value)) { return $Fallback }
    if ($Value.Length -gt 180) { $Value = $Value.Substring(0,180) }
    return $Value
}

function Safe-RelativePath([string]$Relative, [string]$Name) {
    $safeName = Safe-Component $Name 'file.bin'
    if ([string]::IsNullOrWhiteSpace($Relative)) { return $safeName }
    $normalized = $Relative.Replace('\','/').Trim('/')
    $parts = $normalized.Split('/')
    $safe = New-Object System.Collections.Generic.List[string]
    foreach ($part in $parts) {
        if ([string]::IsNullOrWhiteSpace($part) -or $part -eq '.' -or $part -eq '..') { throw 'Unsafe relative path.' }
        $safe.Add((Safe-Component $part 'item'))
    }
    if ($safe.Count -eq 0) { return $safeName }
    return [string]::Join([IO.Path]::DirectorySeparatorChar, $safe)
}

function Get-UniquePath([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $Path }
    $dir = [IO.Path]::GetDirectoryName($Path)
    $base = [IO.Path]::GetFileNameWithoutExtension($Path)
    $ext = [IO.Path]::GetExtension($Path)
    for ($i=1; $i -lt 10000; $i++) {
        $candidate = Join-Path $dir ("$base ($i)$ext")
        if (-not (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    throw 'Could not choose a unique destination filename.'
}

function Constant-TimeEquals([string]$A, [string]$B) {
    if ($null -eq $A -or $null -eq $B) { return $false }
    $left = [Text.Encoding]::UTF8.GetBytes($A)
    $right = [Text.Encoding]::UTF8.GetBytes($B)
    $diff = $left.Length -bxor $right.Length
    $max = [Math]::Max($left.Length,$right.Length)
    for ($i=0; $i -lt $max; $i++) {
        $x = if ($i -lt $left.Length) { $left[$i] } else { 0 }
        $y = if ($i -lt $right.Length) { $right[$i] } else { 0 }
        $diff = $diff -bor ($x -bxor $y)
    }
    return $diff -eq 0
}

function Receive-Client([Net.Sockets.TcpClient]$Client) {
    $Client.NoDelay = $true
    $Client.ReceiveBufferSize = 4MB
    $Client.SendBufferSize = 4MB
    $remote = $Client.Client.RemoteEndPoint.ToString()
    $stream = $Client.GetStream()
    try {
        $gotMagic = Read-Exact $stream $Magic.Length
        if (-not (Bytes-Equal $gotMagic $Magic)) { throw 'Invalid OptiShare PC protocol.' }
        $token = Read-Utf8 $stream 512
        if (-not (Constant-TimeEquals $token $SessionToken)) { $stream.WriteByte(0); return }
        $count = Read-Int32BE $stream
        if ($count -le 0 -or $count -gt 10000) { $stream.WriteByte(0); return }

        $choice = [System.Windows.Forms.MessageBox]::Show(
            "Accept $count item(s) from $remote?`r`n`r`nFiles will be saved in Downloads\OptiShare.",
            'OptiShare incoming transfer',
            [System.Windows.Forms.MessageBoxButtons]::YesNo,
            [System.Windows.Forms.MessageBoxIcon]::Question)
        if ($choice -ne [System.Windows.Forms.DialogResult]::Yes) { $stream.WriteByte(0); return }
        $stream.WriteByte(1)

        if (-not (Test-Path -LiteralPath $DownloadRoot)) { [IO.Directory]::CreateDirectory($DownloadRoot) | Out-Null }
        for ($index=0; $index -lt $count; $index++) {
            $name = Read-Utf8 $stream 4096
            $relative = Read-Utf8 $stream 8192
            $mime = Read-Utf8 $stream 1024
            $size = Read-Int64BE $stream
            if ($size -lt 0 -or $size -gt 2TB) { $stream.WriteByte(0); throw 'Invalid file size.' }
            $safeRelative = Safe-RelativePath $relative $name
            $destination = Join-Path $DownloadRoot $safeRelative
            $fullRoot = [IO.Path]::GetFullPath($DownloadRoot + [IO.Path]::DirectorySeparatorChar)
            $fullDestination = [IO.Path]::GetFullPath($destination)
            if (-not $fullDestination.StartsWith($fullRoot,[StringComparison]::OrdinalIgnoreCase)) { $stream.WriteByte(0); throw 'Unsafe destination path.' }
            $directory = [IO.Path]::GetDirectoryName($fullDestination)
            if (-not (Test-Path -LiteralPath $directory)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
            $fullDestination = Get-UniquePath $fullDestination
            $temp = $fullDestination + '.optishare-part'
            if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
            $stream.WriteByte(1)

            $sha = [Security.Cryptography.SHA256]::Create()
            $file = [IO.File]::Open($temp,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            try {
                $remaining = $size
                $buffer = New-Object byte[] (1024 * 1024)
                while ($remaining -gt 0) {
                    $want = [int][Math]::Min($buffer.Length,$remaining)
                    $read = $stream.Read($buffer,0,$want)
                    if ($read -le 0) { throw 'Connection ended before the file was complete.' }
                    $file.Write($buffer,0,$read)
                    [void]$sha.TransformBlock($buffer,0,$read,$buffer,0)
                    $remaining -= $read
                }
                [void]$sha.TransformFinalBlock((New-Object byte[] 0),0,0)
                $file.Flush($true)
            } finally { $file.Dispose() }

            $expected = Read-Exact $stream 32
            $actual = $sha.Hash
            $sha.Dispose()
            if (-not (Bytes-Equal $actual $expected)) {
                Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
                $stream.WriteByte(0)
                throw "SHA-256 verification failed for $name"
            }
            Move-Item -LiteralPath $temp -Destination $fullDestination -Force
            $stream.WriteByte(1)
        }
        $marker = Read-Int32BE $stream
        if ($marker -ne 0x0F7152E2) { throw 'Invalid completion marker.' }
        $stream.WriteByte(1)
        [System.Windows.Forms.MessageBox]::Show(
            "Transfer complete.`r`nSaved to:`r`n$DownloadRoot",
            'OptiShare',
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
    } catch {
        try { $stream.WriteByte(0) } catch { }
        [System.Windows.Forms.MessageBox]::Show(
            $_.Exception.Message,
            'OptiShare receive failed',
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    } finally {
        try { $stream.Dispose() } catch { }
        try { $Client.Close() } catch { }
    }
}

$udp = New-Object Net.Sockets.UdpClient($DiscoveryPort)
$udp.Client.ReceiveTimeout = 200
$listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Any,$TransferPort)
$listener.Start()

try {
    while ($true) {
        while ($udp.Available -gt 0) {
            $remote = New-Object Net.IPEndPoint([Net.IPAddress]::Any,0)
            $packet = $udp.Receive([ref]$remote)
            $text = [Text.Encoding]::UTF8.GetString($packet)
            if ($text.Trim() -eq 'OPTISHARE_PC_DISCOVER_V1') {
                $response = [Text.Encoding]::UTF8.GetBytes("OPTISHARE_PC_V1|$ComputerName|$TransferPort|$SessionToken|1")
                [void]$udp.Send($response,$response.Length,$remote)
            }
        }
        if ($listener.Pending()) {
            Receive-Client ($listener.AcceptTcpClient())
        }
        Start-Sleep -Milliseconds 80
    }
} finally {
    try { $listener.Stop() } catch { }
    try { $udp.Close() } catch { }
}
