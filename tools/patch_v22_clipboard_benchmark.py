from pathlib import Path


def patch(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8').replace('\r\n','\n')
    if old not in text:
        raise SystemExit(f'anchor not found in {path}: {old[:100]!r}')
    text2 = text.replace(old, new, count)
    p.write_text(text2, encoding='utf-8')

# BrowserReceiveService: clipboard + discard-only benchmark endpoints.
p='app/src/main/java/com/kenan/optishare/transfer/BrowserReceiveService.java'
patch(p,
'''import android.app.PendingIntent;\nimport android.app.Service;\nimport android.content.Intent;''',
'''import android.app.PendingIntent;\nimport android.app.Service;\nimport android.content.ClipData;\nimport android.content.ClipboardManager;\nimport android.content.Intent;''')
patch(p,
'''                if (Method.GET.equals(session.getMethod())) return page(supplied);\n                if (!Method.POST.equals(session.getMethod()) || !"/upload".equals(session.getUri())) {\n                    return text(Response.Status.NOT_FOUND, "Not found");\n                }\n                return receiveUpload(session, supplied);''',
'''                if (Method.GET.equals(session.getMethod())) return page(supplied);\n                if (!Method.POST.equals(session.getMethod())) return text(Response.Status.NOT_FOUND, "Not found");\n                if ("/clipboard".equals(session.getUri())) return receiveClipboard(session, supplied);\n                if ("/benchmark".equals(session.getUri())) return receiveBenchmark(session, supplied);\n                if ("/upload".equals(session.getUri())) return receiveUpload(session, supplied);\n                return text(Response.Status.NOT_FOUND, "Not found");''')
anchor='''    private Response receiveUpload(NanoHTTPD.IHTTPSession session, String supplied) throws Exception {'''
insert='''    private Response receiveClipboard(NanoHTTPD.IHTTPSession session, String supplied) throws Exception {\n        Map<String, String> headers = session.getHeaders();\n        long length = parseLength(headers.get("content-length"));\n        if (length <= 0 || length > 256L * 1024L) {\n            return text(Response.Status.BAD_REQUEST, "Clipboard text must be between 1 B and 256 KB");\n        }\n        String approvalKey = "browser-clipboard:" + supplied + ":" + System.nanoTime();\n        IncomingApproval.begin(approvalKey, "Clipboard from Windows",\n                "Copy " + human(length) + " of text into this phone's clipboard?\\nAccept only if you started this transfer.");\n        if (!IncomingApproval.await(approvalKey, APPROVAL_TIMEOUT_MS)) {\n            return text(Response.Status.FORBIDDEN, "Clipboard transfer declined on the phone");\n        }\n        if (!validToken(supplied)) return text(Response.Status.UNAUTHORIZED, "Session expired");\n        byte[] data = new byte[(int) length];\n        int done = 0;\n        try (InputStream in = session.getInputStream()) {\n            while (done < data.length) {\n                int n = in.read(data, done, data.length - done);\n                if (n < 0) break;\n                if (n == 0) continue;\n                done += n;\n            }\n        }\n        if (done != data.length) throw new IllegalStateException("Clipboard connection ended early");\n        String value = new String(data, StandardCharsets.UTF_8);\n        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);\n        if (clipboard == null) throw new IllegalStateException("Android clipboard service unavailable");\n        clipboard.setPrimaryClip(ClipData.newPlainText("OptiShare clipboard", value));\n        broadcast("clipboard", "Windows clipboard copied to this phone ✓", null, 100);\n        return text(Response.Status.OK, "Clipboard copied successfully");\n    }\n\n    private Response receiveBenchmark(NanoHTTPD.IHTTPSession session, String supplied) throws Exception {\n        Map<String, String> headers = session.getHeaders();\n        long length = parseLength(headers.get("content-length"));\n        if (length < 1024L || length > 32L * 1024L * 1024L) {\n            return text(Response.Status.BAD_REQUEST, "Benchmark payload must be 1 KB to 32 MB");\n        }\n        long started = System.nanoTime();\n        long done = 0L;\n        byte[] buffer = new byte[1024 * 1024];\n        try (InputStream in = session.getInputStream()) {\n            while (done < length) {\n                int want = (int) Math.min(buffer.length, length - done);\n                int n = in.read(buffer, 0, want);\n                if (n < 0) break;\n                if (n == 0) continue;\n                done += n;\n            }\n        }\n        if (done != length) throw new IllegalStateException("Benchmark connection ended early");\n        long elapsedMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);\n        double bytesPerSecond = done / Math.max(0.001d, elapsedMs / 1000d);\n        broadcast("benchmark", "LAN benchmark • " + human((long) bytesPerSecond) + "/s", null, 100);\n        return text(Response.Status.OK, "bytes=" + done + ";elapsed_ms=" + elapsedMs\n                + ";bytes_per_second=" + Math.round(bytesPerSecond));\n    }\n\n'''+anchor
patch(p, anchor, insert)

# RoutePerformanceStore: include PC-local real transfer learning.
p='app/src/main/java/com/kenan/optishare/transfer/RoutePerformanceStore.java'
patch(p,
'''    public static final String ROUTE_DIRECT = "wifi-direct";\n    public static final String ROUTE_LAN = "lan";''',
'''    public static final String ROUTE_DIRECT = "wifi-direct";\n    public static final String ROUTE_LAN = "lan";\n    public static final String ROUTE_PC = "pc-local";''')
patch(p,
'''        int base = ROUTE_DIRECT.equals(route) ? 70 : 60;''',
'''        int base = ROUTE_DIRECT.equals(route) ? 70 : (ROUTE_LAN.equals(route) ? 60 : 55);''')
patch(p,
'''        return "Direct " + score(ROUTE_DIRECT) + " • LAN " + score(ROUTE_LAN);''',
'''        return "Direct " + score(ROUTE_DIRECT) + " • LAN " + score(ROUTE_LAN)\n                + " • PC " + score(ROUTE_PC);''')
patch(p,
'''        return ROUTE_DIRECT.equals(route) || ROUTE_LAN.equals(route);''',
'''        return ROUTE_DIRECT.equals(route) || ROUTE_LAN.equals(route) || ROUTE_PC.equals(route);''')

# PcTransferService: record real PC route success/failure.
p='app/src/main/java/com/kenan/optishare/transfer/PcTransferService.java'
patch(p,
'''    private volatile Socket activeSocket;\n\n    @Override public void onCreate() {\n        super.onCreate();\n        createChannel();''',
'''    private volatile Socket activeSocket;\n    private RoutePerformanceStore routeStore;\n\n    @Override public void onCreate() {\n        super.onCreate();\n        routeStore = new RoutePerformanceStore(this);\n        createChannel();''')
patch(p,
'''                double average = total <= 0 ? 0d : total / Math.max(0.001, durationMs / 1000d);\n                broadcast("completed", "Saved on Windows • SHA-256 verified", 100, 0d,''',
'''                double average = total <= 0 ? 0d : total / Math.max(0.001, durationMs / 1000d);\n                routeStore.recordSuccess(RoutePerformanceStore.ROUTE_PC, average);\n                broadcast("completed", "Saved on Windows • SHA-256 verified", 100, 0d,''')
patch(p,
'''        } catch (Exception error) {\n            broadcast("error", safe(error), 0, 0d, 0L, 0L, 0L, 0d, 0L, 0, 0, 0L);''',
'''        } catch (Exception error) {\n            routeStore.recordFailure(RoutePerformanceStore.ROUTE_PC);\n            broadcast("error", safe(error), 0, 0d, 0L, 0L, 0L, 0d, 0L, 0, 0, 0L);''')
patch(p,
'''        intent.putExtra(TransferService.EXTRA_ROUTE, "pc-local");''',
'''        intent.putExtra(TransferService.EXTRA_ROUTE, RoutePerformanceStore.ROUTE_PC);''')

# Windows receiver: text payloads from Android become actual Windows clipboard content after SHA verification.
p='windows/OptiShare-PC-Receiver.ps1'
patch(p,
'''            Move-Item -LiteralPath $temp -Destination $fullDestination -Force\n            $stream.WriteByte(1)''',
'''            $isClipboardText = ($mime -eq 'text/plain' -and $name.StartsWith('OptiShare Text') -and $size -le 262144)\n            if ($isClipboardText) {\n                $clipboardText = [IO.File]::ReadAllText($temp,[Text.Encoding]::UTF8)\n                [System.Windows.Forms.Clipboard]::SetText($clipboardText)\n                Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue\n            } else {\n                Move-Item -LiteralPath $temp -Destination $fullDestination -Force\n            }\n            $stream.WriteByte(1)''')

# Windows companion: generic session endpoint, clipboard send, and real 8 MB LAN speed test.
p='windows/OptiShare-Companion.ps1'
patch(p,
'''function Get-UploadUri([string]$sessionUrl) {\n    $sessionUrl = $sessionUrl.Trim()''',
'''function Get-SessionEndpointUri([string]$sessionUrl,[string]$endpoint) {\n    $sessionUrl = $sessionUrl.Trim()''')
patch(p,
'''    $builder.Path = '/upload'\n    $builder.Query = 'token=' + [Uri]::EscapeDataString($token)\n    return $builder.Uri\n}\n''',
'''    $builder.Path = $endpoint\n    $builder.Query = 'token=' + [Uri]::EscapeDataString($token)\n    return $builder.Uri\n}\n\nfunction Get-UploadUri([string]$sessionUrl) { return Get-SessionEndpointUri $sessionUrl '/upload' }\n''')
patch(p,
'''$clearButton.Size = New-Object System.Drawing.Size(100,38)\n$form.Controls.Add($clearButton)\n\n$dropHint = New-Object System.Windows.Forms.Label\n$dropHint.Text = 'Tip: drag and drop files anywhere on this window.'\n$dropHint.AutoSize = $true\n$dropHint.ForeColor = [Drawing.Color]::FromArgb(110,185,225)\n$dropHint.Location = New-Object Drawing.Point(285,228)\n$form.Controls.Add($dropHint)''',
'''$clearButton.Size = New-Object System.Drawing.Size(90,38)\n$form.Controls.Add($clearButton)\n\n$clipboardButton = New-Object System.Windows.Forms.Button\n$clipboardButton.Text = 'Send clipboard'\n$clipboardButton.Location = New-Object Drawing.Point(260,218)\n$clipboardButton.Size = New-Object System.Drawing.Size(135,38)\n$form.Controls.Add($clipboardButton)\n\n$benchmarkButton = New-Object System.Windows.Forms.Button\n$benchmarkButton.Text = 'Speed test'\n$benchmarkButton.Location = New-Object Drawing.Point(405,218)\n$benchmarkButton.Size = New-Object System.Drawing.Size(115,38)\n$form.Controls.Add($benchmarkButton)\n\n$dropHint = New-Object System.Windows.Forms.Label\n$dropHint.Text = 'Drag & drop files anywhere.'\n$dropHint.AutoSize = $true\n$dropHint.ForeColor = [Drawing.Color]::FromArgb(110,185,225)\n$dropHint.Location = New-Object Drawing.Point(535,228)\n$form.Controls.Add($dropHint)''')
anchor='''$sendButton.Add_Click({\n'''
handlers=r'''$clipboardButton.Add_Click({
    try {
        if (-not [System.Windows.Forms.Clipboard]::ContainsText()) { throw 'Windows clipboard has no text to send.' }
        $value = [System.Windows.Forms.Clipboard]::GetText()
        if ([string]::IsNullOrEmpty($value)) { throw 'Windows clipboard has no text to send.' }
        $bytes = [Text.Encoding]::UTF8.GetBytes($value)
        if ($bytes.Length -gt 262144) { throw 'Clipboard text is larger than 256 KB.' }
        $uri = Get-SessionEndpointUri $urlBox.Text '/clipboard'
        $status.Text = 'Waiting for phone approval to copy clipboard...'
        [System.Windows.Forms.Application]::DoEvents()
        $request = [System.Net.HttpWebRequest]::Create($uri)
        $request.Method = 'POST'; $request.ContentType = 'text/plain; charset=utf-8'; $request.ContentLength = $bytes.Length
        $request.Timeout = 120000; $request.ReadWriteTimeout = 120000
        $stream = $request.GetRequestStream()
        try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
        $response = $request.GetResponse()
        try { $reader = New-Object IO.StreamReader($response.GetResponseStream()); try { $reply=$reader.ReadToEnd() } finally { $reader.Dispose() } } finally { $response.Dispose() }
        $status.Text = 'Clipboard copied to Android successfully'
    } catch {
        $status.Text = 'Clipboard transfer failed'
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message,'OptiShare clipboard',[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
})

$benchmarkButton.Add_Click({
    try {
        $uri = Get-SessionEndpointUri $urlBox.Text '/benchmark'
        $bytes = New-Object byte[] (8 * 1024 * 1024)
        $status.Text = 'Running real 8 MB LAN upload test...'
        $benchmarkButton.Enabled = $false
        [System.Windows.Forms.Application]::DoEvents()
        $request = [System.Net.HttpWebRequest]::Create($uri)
        $request.Method='POST'; $request.ContentType='application/octet-stream'; $request.ContentLength=$bytes.Length
        $request.Timeout=120000; $request.ReadWriteTimeout=120000; $request.AllowWriteStreamBuffering=$false
        $sw=[Diagnostics.Stopwatch]::StartNew()
        $stream=$request.GetRequestStream()
        try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
        $response=$request.GetResponse()
        try { $reader=New-Object IO.StreamReader($response.GetResponseStream()); try { $reply=$reader.ReadToEnd() } finally { $reader.Dispose() } } finally { $response.Dispose() }
        $sw.Stop()
        $speed=($bytes.Length/[Math]::Max(0.001,$sw.Elapsed.TotalSeconds))/1MB
        $status.Text=('Real LAN test: {0:N1} MB/s • 8 MB in {1:N2}s' -f $speed,$sw.Elapsed.TotalSeconds)
    } catch {
        $status.Text='Speed test failed'
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message,'OptiShare speed test',[System.Windows.Forms.MessageBoxButtons]::OK,[System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    } finally { $benchmarkButton.Enabled=$true }
})

'''+anchor
patch(p, anchor, handlers)

print('clipboard + benchmark integration patched')
