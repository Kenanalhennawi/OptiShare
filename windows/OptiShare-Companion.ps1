Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

[System.Windows.Forms.Application]::EnableVisualStyles()

$script:files = New-Object System.Collections.Generic.List[string]

function Get-UploadUri([string]$sessionUrl) {
    $sessionUrl = $sessionUrl.Trim()
    if ([string]::IsNullOrWhiteSpace($sessionUrl)) { throw 'Paste the Browser Receive address shown by OptiShare.' }
    $uri = [Uri]$sessionUrl
    if ($uri.Scheme -ne 'http') { throw 'OptiShare Browser Receive uses a local http:// address.' }
    $query = [System.Web.HttpUtility]::ParseQueryString($uri.Query)
    $token = $query['token']
    if ([string]::IsNullOrWhiteSpace($token)) { throw 'The OptiShare session link is missing its one-time token.' }
    $builder = New-Object System.UriBuilder($uri)
    $builder.Path = '/upload'
    $builder.Query = 'token=' + [Uri]::EscapeDataString($token)
    return $builder.Uri
}

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

$form = New-Object System.Windows.Forms.Form
$form.Text = 'OptiShare Windows Companion'
$form.Size = New-Object System.Drawing.Size(780,620)
$form.MinimumSize = New-Object System.Drawing.Size(680,520)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [Drawing.Color]::FromArgb(7,26,50)
$form.ForeColor = [Drawing.Color]::White
$form.Font = New-Object Drawing.Font('Segoe UI',10)

$title = New-Object System.Windows.Forms.Label
$title.Text = 'OptiShare Windows Companion'
$title.Font = New-Object Drawing.Font('Segoe UI Semibold',22)
$title.AutoSize = $true
$title.Location = New-Object Drawing.Point(26,22)
$form.Controls.Add($title)

$subtitle = New-Object System.Windows.Forms.Label
$subtitle.Text = 'Send files from Windows to OptiShare over your local network.'
$subtitle.ForeColor = [Drawing.Color]::FromArgb(170,205,230)
$subtitle.AutoSize = $true
$subtitle.Location = New-Object Drawing.Point(30,64)
$form.Controls.Add($subtitle)

$urlLabel = New-Object System.Windows.Forms.Label
$urlLabel.Text = 'Browser Receive address'
$urlLabel.AutoSize = $true
$urlLabel.Location = New-Object Drawing.Point(30,106)
$form.Controls.Add($urlLabel)

$urlBox = New-Object System.Windows.Forms.TextBox
$urlBox.Location = New-Object Drawing.Point(30,132)
$urlBox.Size = New-Object Drawing.Size(700,32)
$urlBox.Anchor = 'Top,Left,Right'
$urlBox.BackColor = [Drawing.Color]::FromArgb(18,47,78)
$urlBox.ForeColor = [Drawing.Color]::White
$urlBox.BorderStyle = 'FixedSingle'
$form.Controls.Add($urlBox)

$hint = New-Object System.Windows.Forms.Label
$hint.Text = 'On Android: Receive → Browser / PC → Start Browser Receive, then paste the shown http:// address here.'
$hint.ForeColor = [Drawing.Color]::FromArgb(135,175,205)
$hint.AutoSize = $true
$hint.Location = New-Object Drawing.Point(30,168)
$form.Controls.Add($hint)

$addButton = New-Object System.Windows.Forms.Button
$addButton.Text = 'Add files'
$addButton.Location = New-Object Drawing.Point(30,205)
$addButton.Size = New-Object Drawing.Size(120,38)
$form.Controls.Add($addButton)

$clearButton = New-Object System.Windows.Forms.Button
$clearButton.Text = 'Clear'
$clearButton.Location = New-Object Drawing.Point(160,205)
$clearButton.Size = New-Object Drawing.Size(100,38)
$form.Controls.Add($clearButton)

$list = New-Object System.Windows.Forms.ListBox
$list.Location = New-Object Drawing.Point(30,255)
$list.Size = New-Object Drawing.Size(700,190)
$list.Anchor = 'Top,Bottom,Left,Right'
$list.BackColor = [Drawing.Color]::FromArgb(13,40,68)
$list.ForeColor = [Drawing.Color]::White
$list.BorderStyle = 'FixedSingle'
$form.Controls.Add($list)

$status = New-Object System.Windows.Forms.Label
$status.Text = 'Ready'
$status.Location = New-Object Drawing.Point(30,458)
$status.Size = New-Object Drawing.Size(700,26)
$status.Anchor = 'Bottom,Left,Right'
$status.ForeColor = [Drawing.Color]::FromArgb(160,205,235)
$form.Controls.Add($status)

$progress = New-Object System.Windows.Forms.ProgressBar
$progress.Location = New-Object Drawing.Point(30,490)
$progress.Size = New-Object Drawing.Size(700,16)
$progress.Anchor = 'Bottom,Left,Right'
$progress.Minimum = 0
$progress.Maximum = 100
$form.Controls.Add($progress)

$sendButton = New-Object System.Windows.Forms.Button
$sendButton.Text = 'Send selected files →'
$sendButton.Location = New-Object Drawing.Point(30,520)
$sendButton.Size = New-Object Drawing.Size(700,48)
$sendButton.Anchor = 'Bottom,Left,Right'
$sendButton.Font = New-Object Drawing.Font('Segoe UI Semibold',11)
$form.Controls.Add($sendButton)

$dialog = New-Object System.Windows.Forms.OpenFileDialog
$dialog.Multiselect = $true
$dialog.Title = 'Choose files to send with OptiShare'

$addButton.Add_Click({
    if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        foreach ($path in $dialog.FileNames) {
            if (-not $script:files.Contains($path)) {
                $script:files.Add($path)
                $info = Get-Item -LiteralPath $path
                [void]$list.Items.Add("$($info.Name)  •  $(Format-Bytes $info.Length)")
            }
        }
        $status.Text = "$($script:files.Count) file(s) queued"
    }
})

$clearButton.Add_Click({
    $script:files.Clear()
    $list.Items.Clear()
    $progress.Value = 0
    $status.Text = 'Ready'
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
                        $filePct = if ($file.Length -gt 0) { [Math]::Min(100,[int](($sent * 100L) / $file.Length)) } else { 100 }
                        $overall = [int]((($completed + ($filePct / 100.0)) * 100.0) / $script:files.Count)
                        $progress.Value = [Math]::Min(100,[Math]::Max(0,$overall))
                        $status.Text = "Sending $($file.Name) • $(Format-Bytes $sent) / $(Format-Bytes $file.Length)"
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

        $status.Text = "Complete ✓ • $completed file(s) sent"
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

[void]$form.ShowDialog()
