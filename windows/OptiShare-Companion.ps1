Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Web

[System.Windows.Forms.Application]::EnableVisualStyles()

$script:files = New-Object System.Collections.Generic.List[string]
$script:allowExit = $false
$script:receiverProcess = $null

function Start-Receiver {
    $receiverScript = Join-Path $PSScriptRoot 'OptiShare-PC-Receiver.ps1'
    if (-not (Test-Path -LiteralPath $receiverScript)) { return }
    try {
        $script:receiverProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
            '-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $receiverScript + '"')
        ) -WindowStyle Hidden -PassThru
    } catch {
        $script:receiverProcess = $null
    }
}

function Stop-Receiver {
    if ($null -ne $script:receiverProcess) {
        try {
            if (-not $script:receiverProcess.HasExited) { $script:receiverProcess.Kill() }
        } catch { }
        $script:receiverProcess = $null
    }
}

function Get-SessionEndpointUri([string]$sessionUrl,[string]$endpoint) {
    $sessionUrl = $sessionUrl.Trim()
    if ([string]::IsNullOrWhiteSpace($sessionUrl)) { throw 'Paste the Browser Receive address shown by OptiShare.' }
    $uri = [Uri]$sessionUrl
    if ($uri.Scheme -ne 'http') { throw 'OptiShare Browser Receive uses a local http:// address.' }
    $query = [System.Web.HttpUtility]::ParseQueryString($uri.Query)
    $token = $query['token']
    if ([string]::IsNullOrWhiteSpace($token)) { throw 'The OptiShare session link is missing its one-time token.' }
    $builder = New-Object System.UriBuilder($uri)
    $builder.Path = $endpoint
    $builder.Query = 'token=' + [Uri]::EscapeDataString($token)
    return $builder.Uri
}

function Get-UploadUri([string]$sessionUrl) { return Get-SessionEndpointUri $sessionUrl '/upload' }

function Get-MimeType([string]$path) {
    switch ([IO.Path]::GetExtension($path).ToLowerInvariant()) {
        '.jpg' { 'image/jpeg' }
        '.jpeg' { 'image/jpeg' }
        '.png' { 'image/png' }
        '.gif' { 'image/gif' }
        '.webp' { 'image/webp' }
        '.mp4' { 'video/mp4' }
        '.mov' { 'video/quicktime' }
        '.mp3' { 'audio/mpeg' }
        '.wav' { 'audio/wav' }
        '.pdf' { 'application/pdf' }
        '.apk' { 'application/vnd.android.package-archive' }
        '.zip' { 'application/zip' }
        '.txt' { 'text/plain' }
        default { 'application/octet-stream' }
    }
}

function Format-Bytes([long]$value) {
    if ($value -ge 1GB) { return ('{0:N2} GB' -f ($value / 1GB)) }
    if ($value -ge 1MB) { return ('{0:N2} MB' -f ($value / 1MB)) }
    if ($value -ge 1KB) { return ('{0:N1} KB' -f ($value / 1KB)) }
    return "$value B"
}

function Add-FileToQueue([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
    if (-not $script:files.Contains($path)) {
        $script:files.Add($path)
        $info = Get-Item -LiteralPath $path
        [void]$list.Items.Add("$($info.Name) - $(Format-Bytes $info.Length)")
    }
    $status.Text = "$($script:files.Count) file(s) queued"
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'OptiShare Windows Companion'
$form.Size = New-Object System.Drawing.Size(780,650)
$form.MinimumSize = New-Object System.Drawing.Size(680,550)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [Drawing.Color]::FromArgb(7,26,50)
$form.ForeColor = [Drawing.Color]::White
$form.Font = New-Object Drawing.Font('Segoe UI',10)
$form.AllowDrop = $true

$title = New-Object System.Windows.Forms.Label
$title.Text = 'OptiShare Windows Companion'
$title.Font = New-Object Drawing.Font('Segoe UI Semibold',22)
$title.AutoSize = $true
$title.Location = New-Object Drawing.Point(26,22)
$form.Controls.Add($title)

$subtitle = New-Object System.Windows.Forms.Label
$subtitle.Text = 'Send to Android and receive from Android over your local network.'
$subtitle.ForeColor = [Drawing.Color]::FromArgb(170,205,230)
$subtitle.AutoSize = $true
$subtitle.Location = New-Object Drawing.Point(30,64)
$form.Controls.Add($subtitle)

$receiveBadge = New-Object System.Windows.Forms.Label
$receiveBadge.Text = 'PC receiver: starting...'
$receiveBadge.AutoSize = $true
$receiveBadge.ForeColor = [Drawing.Color]::FromArgb(90,220,165)
$receiveBadge.Location = New-Object Drawing.Point(30,92)
$form.Controls.Add($receiveBadge)

$urlLabel = New-Object System.Windows.Forms.Label
$urlLabel.Text = 'Android Browser Receive address (for Windows to Android)'
$urlLabel.AutoSize = $true
$urlLabel.Location = New-Object Drawing.Point(30,122)
$form.Controls.Add($urlLabel)

$urlBox = New-Object System.Windows.Forms.TextBox
$urlBox.Location = New-Object Drawing.Point(30,148)
$urlBox.Size = New-Object Drawing.Size(700,32)
$urlBox.Anchor = 'Top,Left,Right'
$urlBox.BackColor = [Drawing.Color]::FromArgb(18,47,78)
$urlBox.ForeColor = [Drawing.Color]::White
$urlBox.BorderStyle = 'FixedSingle'
$form.Controls.Add($urlBox)

$hint = New-Object System.Windows.Forms.Label
$hint.Text = 'Android to Windows: keep this Companion running. The PC appears automatically in Nearby devices.'
$hint.ForeColor = [Drawing.Color]::FromArgb(135,175,205)
$hint.AutoSize = $true
$hint.Location = New-Object Drawing.Point(30,184)
$form.Controls.Add($hint)

$addButton = New-Object System.Windows.Forms.Button
$addButton.Text = 'Add files'
$addButton.Location = New-Object Drawing.Point(30,218)
$addButton.Size = New-Object Drawing.Size(120,38)
$form.Controls.Add($addButton)

$clearButton = New-Object System.Windows.Forms.Button
$clearButton.Text = 'Clear'
$clearButton.Location = New-Object Drawing.Point(160,218)
$clearButton.Size = New-Object Drawing.Size(90,38)
$form.Controls.Add($clearButton)

$clipboardButton = New-Object System.Windows.Forms.Button
$clipboardButton.Text = 'Send clipboard'
$clipboardButton.Location = New-Object Drawing.Point(260,218)
$clipboardButton.Size = New-Object System.Drawing.Size(135,38)
$form.Controls.Add($clipboardButton)

$benchmarkButton = New-Object System.Windows.Forms.Button
$benchmarkButton.Text = 'Speed test'
$benchmarkButton.Location = New-Object Drawing.Point(405,218)
$benchmarkButton.Size = New-Object System.Drawing.Size(115,38)
$form.Controls.Add($benchmarkButton)

$dropHint = New-Object System.Windows.Forms.Label
$dropHint.Text = 'Drag & drop files anywhere.'
$dropHint.AutoSize = $true
$dropHint.ForeColor = [Drawing.Color]::FromArgb(110,185,225)
$dropHint.Location = New-Object Drawing.Point(535,228)
$form.Controls.Add($dropHint)

$list = New-Object System.Windows.Forms.ListBox
$list.Location = New-Object Drawing.Point(30,270)
$list.Size = New-Object Drawing.Size(700,200)
$list.Anchor = 'Top,Bottom,Left,Right'
$list.BackColor = [Drawing.Color]::FromArgb(13,40,68)
$list.ForeColor = [Drawing.Color]::White
$list.BorderStyle = 'FixedSingle'
$form.Controls.Add($list)

$status = New-Object System.Windows.Forms.Label
$status.Text = 'Ready'
$status.Location = New-Object Drawing.Point(30,485)
$status.Size = New-Object Drawing.Size(700,26)
$status.Anchor = 'Bottom,Left,Right'
$status.ForeColor = [Drawing.Color]::FromArgb(160,205,235)
$form.Controls.Add($status)

$progress = New-Object System.Windows.Forms.ProgressBar
$progress.Location = New-Object Drawing.Point(30,515)
$progress.Size = New-Object Drawing.Size(700,16)
$progress.Anchor = 'Bottom,Left,Right'
$progress.Minimum = 0
$progress.Maximum = 100
$form.Controls.Add($progress)

$sendButton = New-Object System.Windows.Forms.Button
$sendButton.Text = 'Send selected files to Android'
$sendButton.Location = New-Object Drawing.Point(30,545)
$sendButton.Size = New-Object Drawing.Size(700,48)
$sendButton.Anchor = 'Bottom,Left,Right'
$sendButton.Font = New-Object Drawing.Font('Segoe UI Semibold',11)
$form.Controls.Add($sendButton)

$dialog = New-Object System.Windows.Forms.OpenFileDialog
$dialog.Multiselect = $true
$dialog.Title = 'Choose files to send with OptiShare'

$trayMenu = New-Object System.Windows.Forms.ContextMenuStrip
$showItem = $trayMenu.Items.Add('Show OptiShare')
$exitItem = $trayMenu.Items.Add('Exit')
$tray = New-Object System.Windows.Forms.NotifyIcon
$tray.Text = 'OptiShare - ready to receive'
$tray.Icon = [Drawing.SystemIcons]::Information
$tray.ContextMenuStrip = $trayMenu
$tray.Visible = $true

$showWindow = {
    $form.Show()
    $form.WindowState = [System.Windows.Forms.FormWindowState]::Normal
    $form.Activate()
}
$showItem.Add_Click($showWindow)
$tray.Add_DoubleClick($showWindow)
$exitItem.Add_Click({
    $script:allowExit = $true
    Stop-Receiver
    $tray.Visible = $false
    $form.Close()
})

$form.Add_Resize({
    if ($form.WindowState -eq [System.Windows.Forms.FormWindowState]::Minimized) {
        $form.Hide()
        $tray.ShowBalloonTip(1500,'OptiShare','Still running and ready to receive from Android.',[System.Windows.Forms.ToolTipIcon]::Info)
    }
})

$form.Add_FormClosing({
    param($sender,$e)
    if (-not $script:allowExit) {
        $e.Cancel = $true
        $form.Hide()
        $tray.ShowBalloonTip(1500,'OptiShare','Running in the tray and ready to receive.',[System.Windows.Forms.ToolTipIcon]::Info)
    }
})

$form.Add_DragEnter({
    param($sender,$e)
    if ($e.Data.GetDataPresent([System.Windows.Forms.DataFormats]::FileDrop)) {
        $e.Effect = [System.Windows.Forms.DragDropEffects]::Copy
    }
})
$form.Add_DragDrop({
    param($sender,$e)
    foreach ($path in [string[]]$e.Data.GetData([System.Windows.Forms.DataFormats]::FileDrop)) { Add-FileToQueue $path }
})

$addButton.Add_Click({
    if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        foreach ($path in $dialog.FileNames) { Add-FileToQueue $path }
    }
})

$clearButton.Add_Click({
    $script:files.Clear()
    $list.Items.Clear()
    $progress.Value = 0
    $status.Text = 'Ready'
})

$clipboardButton.Add_Click({
    try {
        if (-not [System.Windows.Forms.Clipboard]::ContainsText()) { throw 'Windows clipboard has no text to send.' }
        $bytes=[Text.Encoding]::UTF8.GetBytes([System.Windows.Forms.Clipboard]::GetText())
        if ($bytes.Length -le 0 -or $bytes.Length -gt 262144) { throw 'Clipboard text must be between 1 B and 256 KB.' }
        $uri=Get-SessionEndpointUri $urlBox.Text '/clipboard'
        $status.Text='Waiting for phone approval to copy clipboard...'; [System.Windows.Forms.Application]::DoEvents()
        $request=[System.Net.HttpWebRequest]::Create($uri); $request.Method='POST'; $request.ContentType='text/plain; charset=utf-8'; $request.ContentLength=$bytes.Length; $request.Timeout=120000; $request.ReadWriteTimeout=120000
        $stream=$request.GetRequestStream(); try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
        $response=$request.GetResponse(); try{$reader=New-Object IO.StreamReader($response.GetResponseStream());try{[void]$reader.ReadToEnd()}finally{$reader.Dispose()}}finally{$response.Dispose()}
        $status.Text='Clipboard copied to Android successfully'
    } catch { $status.Text='Clipboard transfer failed'; [System.Windows.Forms.MessageBox]::Show($_.Exception.Message,'OptiShare clipboard',[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Error)|Out-Null }
})

$benchmarkButton.Add_Click({
    try {
        $uri=Get-SessionEndpointUri $urlBox.Text '/benchmark'; $bytes=New-Object byte[] (8*1024*1024)
        $status.Text='Running real 8 MB LAN upload test...'; $benchmarkButton.Enabled=$false; [System.Windows.Forms.Application]::DoEvents()
        $request=[System.Net.HttpWebRequest]::Create($uri); $request.Method='POST'; $request.ContentType='application/octet-stream'; $request.ContentLength=$bytes.Length; $request.Timeout=120000; $request.ReadWriteTimeout=120000; $request.AllowWriteStreamBuffering=$false
        $sw=[Diagnostics.Stopwatch]::StartNew(); $stream=$request.GetRequestStream(); try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
        $response=$request.GetResponse(); try{$reader=New-Object IO.StreamReader($response.GetResponseStream());try{$reply=$reader.ReadToEnd()}finally{$reader.Dispose()}}finally{$response.Dispose()}; $sw.Stop()
        $speed=($bytes.Length/[Math]::Max(0.001,$sw.Elapsed.TotalSeconds))/1MB
        $status.Text=('Real LAN test: {0:N1} MB/s • 8 MB in {1:N2}s' -f $speed,$sw.Elapsed.TotalSeconds)
    } catch { $status.Text='Speed test failed'; [System.Windows.Forms.MessageBox]::Show($_.Exception.Message,'OptiShare speed test',[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Error)|Out-Null }
    finally { $benchmarkButton.Enabled=$true }
})

$sendButton.Add_Click({
    try {
        if ($script:files.Count -eq 0) { throw 'Add at least one file first.' }
        $uploadUri = Get-UploadUri $urlBox.Text
        $sendButton.Enabled = $false
        $addButton.Enabled = $false
        $clearButton.Enabled = $false
        $progress.Value = 0
        $completed = 0

        foreach ($path in @($script:files)) {
            $file = Get-Item -LiteralPath $path
            $status.Text = "Waiting for phone approval: $($file.Name)"
            [System.Windows.Forms.Application]::DoEvents()

            $request = [System.Net.HttpWebRequest]::Create($uploadUri)
            $request.Method = 'POST'
            $request.ContentType = Get-MimeType $file.FullName
            $request.ContentLength = $file.Length
            $request.Headers.Add('X-OptiShare-Name', [Uri]::EscapeDataString($file.Name))
            $request.Timeout = 1800000
            $request.ReadWriteTimeout = 1800000
            $request.AllowWriteStreamBuffering = $false

            $input = [IO.File]::OpenRead($file.FullName)
            try {
                $output = $request.GetRequestStream()
                try {
                    $buffer = New-Object byte[] (1024 * 1024)
                    [long]$sent = 0
                    while (($read = $input.Read($buffer,0,$buffer.Length)) -gt 0) {
                        $output.Write($buffer,0,$read)
                        $sent += $read
                        $filePct = if ($file.Length -gt 0) { [Math]::Min(100,[int](($sent * 100) / $file.Length)) } else { 100 }
                        $overall = [int]((($completed + ($filePct / 100.0)) * 100.0) / $script:files.Count)
                        $progress.Value = [Math]::Min(100,[Math]::Max(0,$overall))
                        $status.Text = "Sending $($file.Name) - $(Format-Bytes $sent) / $(Format-Bytes $file.Length)"
                        [System.Windows.Forms.Application]::DoEvents()
                    }
                } finally { $output.Dispose() }
            } finally { $input.Dispose() }

            try {
                $response = $request.GetResponse()
                try {
                    $reader = New-Object IO.StreamReader($response.GetResponseStream())
                    try { $reply = $reader.ReadToEnd() } finally { $reader.Dispose() }
                } finally { $response.Dispose() }
            } catch [System.Net.WebException] {
                $detail = $_.Exception.Message
                if ($_.Exception.Response) {
                    $reader = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())
                    try { $detail = $reader.ReadToEnd() } finally { $reader.Dispose(); $_.Exception.Response.Dispose() }
                }
                throw "$($file.Name): $detail"
            }
            $completed++
            $progress.Value = [int](($completed * 100.0) / $script:files.Count)
            $status.Text = "Saved on phone: $($file.Name)"
            [System.Windows.Forms.Application]::DoEvents()
        }

        $status.Text = "Complete - $completed file(s) sent"
        [System.Windows.Forms.MessageBox]::Show("$completed file(s) sent successfully.",'OptiShare',[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
    } catch {
        $status.Text = 'Transfer failed'
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message,'OptiShare transfer failed',[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    } finally {
        $sendButton.Enabled = $true
        $addButton.Enabled = $true
        $clearButton.Enabled = $true
    }
})

Start-Receiver
if ($null -ne $script:receiverProcess) {
    $receiveBadge.Text = 'PC receiver: ready - Android will discover this PC automatically'
} else {
    $receiveBadge.Text = 'PC receiver: could not start - check Windows firewall / PowerShell'
    $receiveBadge.ForeColor = [Drawing.Color]::FromArgb(255,130,130)
}

try {
    [void]$form.ShowDialog()
} finally {
    Stop-Receiver
    $tray.Visible = $false
    $tray.Dispose()
}
