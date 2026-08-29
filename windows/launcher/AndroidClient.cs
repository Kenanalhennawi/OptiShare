using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Net.Sockets;

namespace OptiShare.Windows;

internal static class AndroidClient
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(30) };

    public static async Task UploadAsync(AndroidDevice device, string path, Func<string,bool> confirm, Action<long,long> progress)
    {
        if(device.ProtocolVersion>=2){await UploadSecureAsync(device,path,confirm,progress);return;}
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

    public static async Task SendClipboardAsync(AndroidDevice device, string value, Func<string,bool> confirm)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        if (bytes.Length is < 1 or > 262144) throw new InvalidOperationException("Clipboard text must be between 1 B and 256 KB.");
        if(device.ProtocolVersion>=2){await SendSecureClipboardAsync(device,bytes,confirm);return;}
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

    private static async Task UploadSecureAsync(AndroidDevice device,string path,Func<string,bool> confirm,Action<long,long> progress)
    {
        var info=new FileInfo(path);await using var session=await OpenSecureAsync(device,confirm);await session.WriteClient([1]);
        using(var metadata=new MemoryStream()){WriteUtf8(metadata,info.Name,4096);WriteUtf8(metadata,Mime(path),1024);var size=new byte[8];BinaryPrimitives.WriteInt64BigEndian(size,info.Length);metadata.Write(size);await session.WriteClient(metadata.ToArray());}
        if(!Ok(await session.ReadServer()))throw new IOException("Android declined the encrypted file.");
        using var sha=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);await using var input=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read,1024*1024,true);var buffer=new byte[1024*1024];long sent=0;int read;
        while((read=await input.ReadAsync(buffer))>0){var chunk=read==buffer.Length?buffer:buffer[..read];await session.WriteClient(chunk);sha.AppendData(buffer,0,read);sent+=read;progress(sent,info.Length);}
        await session.WriteClient(sha.GetHashAndReset());if(!Ok(await session.ReadServer()))throw new CryptographicException("Android SHA-256 verification failed.");
    }

    private static async Task SendSecureClipboardAsync(AndroidDevice device,byte[] value,Func<string,bool> confirm)
    {
        await using var session=await OpenSecureAsync(device,confirm);await session.WriteClient([2]);await session.WriteClient(value);if(!Ok(await session.ReadServer()))throw new IOException("Android declined the encrypted clipboard.");
    }

    private static async Task<AndroidSecureSession> OpenSecureAsync(AndroidDevice device,Func<string,bool> confirm)
    {
        var tcp=new TcpClient{NoDelay=true,SendBufferSize=4*1024*1024,ReceiveBufferSize=4*1024*1024};await tcp.ConnectAsync(device.Address,device.SecurePort);var stream=tcp.GetStream();
        await stream.WriteAsync(SecureChannel.Magic);await WriteUtf8Async(stream,device.Token,512);using var own=ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);var clientPublic=own.ExportSubjectPublicKeyInfo();var salt=RandomNumberGenerator.GetBytes(32);await WriteInt32Async(stream,clientPublic.Length);await stream.WriteAsync(clientPublic);await stream.WriteAsync(salt);
        var serverLength=await ReadInt32Async(stream);if(serverLength is <64 or >512)throw new CryptographicException("Invalid Android secure key.");var serverPublic=await ReadExactAsync(stream,serverLength);using var peer=ECDiffieHellman.Create();peer.ImportSubjectPublicKeyInfo(serverPublic,out var consumed);if(consumed!=serverPublic.Length)throw new CryptographicException("Trailing Android key data.");var shared=own.DeriveRawSecretAgreement(peer.PublicKey);var key=SecureChannel.DeriveKey(shared,salt);CryptographicOperations.ZeroMemory(shared);var code=SecureChannel.SecurityCode(clientPublic,serverPublic,salt);var session=new AndroidSecureSession(tcp,stream,key);
        var accepted=confirm(code);await session.WriteClient(Encoding.ASCII.GetBytes(accepted?"ACCEPT":"DECLINE"));var android=Encoding.ASCII.GetString(await session.ReadServer());if(!accepted||android!="ACCEPT"){await session.DisposeAsync();throw new CryptographicException("Security code confirmation was declined.");}return session;
    }

    private static bool Ok(byte[] value)=>value.Length==1&&value[0]==1;
    private static void WriteUtf8(Stream stream,string value,int max){var bytes=Encoding.UTF8.GetBytes(value??"");if(bytes.Length>max)throw new IOException("Metadata too long.");Span<byte> length=stackalloc byte[4];BinaryPrimitives.WriteInt32BigEndian(length,bytes.Length);stream.Write(length);stream.Write(bytes);}
    private static async Task WriteUtf8Async(Stream stream,string value,int max){var bytes=Encoding.UTF8.GetBytes(value??"");if(bytes.Length>max)throw new IOException("Metadata too long.");await WriteInt32Async(stream,bytes.Length);await stream.WriteAsync(bytes);}
    private static async Task WriteInt32Async(Stream stream,int value){var bytes=new byte[4];BinaryPrimitives.WriteInt32BigEndian(bytes,value);await stream.WriteAsync(bytes);}
    private static async Task<int> ReadInt32Async(Stream stream)=>BinaryPrimitives.ReadInt32BigEndian(await ReadExactAsync(stream,4));
    private static async Task<byte[]> ReadExactAsync(Stream stream,int count){var data=new byte[count];var offset=0;while(offset<count){var read=await stream.ReadAsync(data.AsMemory(offset,count-offset));if(read<=0)throw new EndOfStreamException();offset+=read;}return data;}

    private sealed class AndroidSecureSession(TcpClient tcp,NetworkStream stream,byte[] key):IAsyncDisposable
    {
        private long sent,received;
        internal async Task WriteClient(byte[] plain){var record=SecureChannel.Encrypt(key,sent++,true,plain);await WriteInt32Async(stream,record.Length);await stream.WriteAsync(record);}
        internal async Task<byte[]> ReadServer(){var length=await ReadInt32Async(stream);if(length is <30 or >SecureChannel.MaxRecordBytes)throw new CryptographicException("Invalid Android secure record length.");var record=await ReadExactAsync(stream,length);return SecureChannel.Decrypt(key,received++,false,record);}
        public ValueTask DisposeAsync(){CryptographicOperations.ZeroMemory(key);stream.Dispose();tcp.Dispose();return ValueTask.CompletedTask;}
    }
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
