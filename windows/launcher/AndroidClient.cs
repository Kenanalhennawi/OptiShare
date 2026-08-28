using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;

namespace OptiShare.Windows;

internal static class AndroidClient
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(30) };

    public static async Task UploadAsync(AndroidDevice device, string path, Action<long,long> progress)
    {
        var file = new FileInfo(path);
        await using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, true);
        using var content = new ProgressStreamContent(input, file.Length, progress);
        content.Headers.ContentType = new MediaTypeHeaderValue(Mime(path));
        using var request = new HttpRequestMessage(HttpMethod.Post, Endpoint(device, "/upload")) { Content = content };
        request.Headers.Add("X-OptiShare-Name", Uri.EscapeDataString(file.Name));
        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseContentRead);
        var body = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException($"{file.Name}: {body}");
    }

    public static async Task SendClipboardAsync(AndroidDevice device, string value)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        if (bytes.Length is < 1 or > 262144) throw new InvalidOperationException("Clipboard text must be between 1 B and 256 KB.");
        using var content = new ByteArrayContent(bytes); content.Headers.ContentType = MediaTypeHeaderValue.Parse("text/plain; charset=utf-8");
        using var response = await Http.PostAsync(Endpoint(device, "/clipboard"), content);
        var body = await response.Content.ReadAsStringAsync(); if (!response.IsSuccessStatusCode) throw new InvalidOperationException(body);
    }

    public static async Task<(double Speed,double Seconds)> BenchmarkAsync(AndroidDevice device)
    {
        var bytes = new byte[8 * 1024 * 1024]; var watch = Stopwatch.StartNew();
        using var content = new ByteArrayContent(bytes); content.Headers.ContentType = MediaTypeHeaderValue.Parse("application/octet-stream");
        using var response = await Http.PostAsync(Endpoint(device, "/benchmark"), content); watch.Stop();
        var body = await response.Content.ReadAsStringAsync(); if (!response.IsSuccessStatusCode) throw new InvalidOperationException(body);
        return (bytes.Length / Math.Max(.001, watch.Elapsed.TotalSeconds), watch.Elapsed.TotalSeconds);
    }

    private static Uri Endpoint(AndroidDevice d, string path) => new UriBuilder(Uri.UriSchemeHttp, d.Address.ToString(), d.Port, path, "token=" + Uri.EscapeDataString(d.Token)).Uri;
    private static string Mime(string path) => Path.GetExtension(path).ToLowerInvariant() switch { ".jpg" or ".jpeg"=>"image/jpeg", ".png"=>"image/png", ".gif"=>"image/gif", ".webp"=>"image/webp", ".mp4"=>"video/mp4", ".mov"=>"video/quicktime", ".mp3"=>"audio/mpeg", ".wav"=>"audio/wav", ".pdf"=>"application/pdf", ".apk"=>"application/vnd.android.package-archive", ".zip"=>"application/zip", ".txt"=>"text/plain", _=>"application/octet-stream" };
}

internal sealed class ProgressStreamContent(Stream source, long length, Action<long,long> progress) : HttpContent
{
    protected override async Task SerializeToStreamAsync(Stream target, TransportContext? context)
    {
        var buffer = new byte[1024 * 1024]; long sent = 0; int read;
        while ((read = await source.ReadAsync(buffer)) > 0) { await target.WriteAsync(buffer.AsMemory(0, read)); sent += read; progress(sent, length); }
    }
    protected override bool TryComputeLength(out long computed) { computed = length; return true; }
    protected override void Dispose(bool disposing) { if (disposing) source.Dispose(); base.Dispose(disposing); }
}
