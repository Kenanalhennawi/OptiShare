using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace OptiShare.Windows;

internal sealed class PcReceiver(Func<string,bool> approve, Action<string> notify) : IDisposable
{
    private const int DiscoveryPort=49891, TransferPort=49890;
    private static readonly byte[] Magic=Encoding.ASCII.GetBytes("OPTISHARE-PC-1\n");
    private readonly CancellationTokenSource stop=new(); private readonly string token=Convert.ToHexString(RandomNumberGenerator.GetBytes(24)).ToLowerInvariant();
    private UdpClient? udp; private TcpListener? listener; private Task? discoveryTask, receiveTask;

    public Task StartAsync()
    {
        udp=new UdpClient(DiscoveryPort); udp.EnableBroadcast=true; listener=new TcpListener(IPAddress.Any,TransferPort); listener.Start();
        discoveryTask=Task.Run(()=>DiscoveryLoop(stop.Token)); receiveTask=Task.Run(()=>ReceiveLoop(stop.Token)); return Task.CompletedTask;
    }

    private async Task DiscoveryLoop(CancellationToken ct)
    {
        while(!ct.IsCancellationRequested) try { var packet=await udp!.ReceiveAsync(ct); if(Encoding.UTF8.GetString(packet.Buffer).Trim()!="OPTISHARE_PC_DISCOVER_V1")continue;
            var name=(Environment.MachineName??"Windows-PC").Replace('|','-'); var reply=Encoding.UTF8.GetBytes($"OPTISHARE_PC_V1|{name}|{TransferPort}|{token}|1"); await udp.SendAsync(reply,packet.RemoteEndPoint,ct);
        } catch(OperationCanceledException){return;} catch(SocketException) when(ct.IsCancellationRequested){return;} catch{ }
    }

    private async Task ReceiveLoop(CancellationToken ct)
    {
        while(!ct.IsCancellationRequested) try { var client=await listener!.AcceptTcpClientAsync(ct); _=Task.Run(()=>ReceiveClient(client,ct),ct); }
        catch(OperationCanceledException){return;} catch(SocketException) when(ct.IsCancellationRequested){return;}
    }

    private async Task ReceiveClient(TcpClient client,CancellationToken ct)
    {
        client.NoDelay=true; client.ReceiveBufferSize=4*1024*1024; client.SendBufferSize=4*1024*1024;
        await using var stream=client.GetStream();
        try
        {
            if(!CryptographicOperations.FixedTimeEquals(await ReadExact(stream,Magic.Length,ct),Magic))throw new IOException("Invalid OptiShare PC protocol.");
            var supplied=await ReadUtf8(stream,512,ct); if(!FixedToken(supplied)){await stream.WriteAsync(new byte[]{0},ct);return;}
            var count=await ReadInt32(stream,ct); if(count is <1 or >10000){await stream.WriteAsync(new byte[]{0},ct);return;}
            if(!approve($"Accept {count} item(s) from {client.Client.RemoteEndPoint}?\r\n\r\nFiles will be saved in Downloads\\OptiShare.")){await stream.WriteAsync(new byte[]{0},ct);return;}
            await stream.WriteAsync(new byte[]{1},ct); var root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),"Downloads","OptiShare");Directory.CreateDirectory(root);
            for(var index=0;index<count;index++)
            {
                var name=await ReadUtf8(stream,4096,ct);var relative=await ReadUtf8(stream,8192,ct);var mime=await ReadUtf8(stream,1024,ct);var size=await ReadInt64(stream,ct);
                if(size is <0 or >2199023255552L){await stream.WriteAsync(new byte[]{0},ct);throw new IOException("Invalid file size.");}
                var destination=SafeDestination(root,relative,name);Directory.CreateDirectory(Path.GetDirectoryName(destination)!);destination=Unique(destination);var temp=destination+".optishare-part";if(File.Exists(temp))File.Delete(temp);
                await stream.WriteAsync(new byte[]{1},ct);await using(var output=new FileStream(temp,FileMode.CreateNew,FileAccess.Write,FileShare.None,1024*1024,true))
                using(var sha=IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
                {
                    var remaining=size;var buffer=new byte[1024*1024];while(remaining>0){var read=await stream.ReadAsync(buffer.AsMemory(0,(int)Math.Min(buffer.Length,remaining)),ct);if(read<=0)throw new EndOfStreamException("Connection ended before the file was complete.");await output.WriteAsync(buffer.AsMemory(0,read),ct);sha.AppendData(buffer,0,read);remaining-=read;}
                    await output.FlushAsync(ct);output.Flush(true);var expected=await ReadExact(stream,32,ct);var actual=sha.GetHashAndReset();if(!CryptographicOperations.FixedTimeEquals(actual,expected)){File.Delete(temp);await stream.WriteAsync(new byte[]{0},ct);throw new IOException($"SHA-256 verification failed for {name}");}
                }
                if(mime=="text/plain"&&name.StartsWith("OptiShare Text",StringComparison.Ordinal)&&size<=262144){var text=await File.ReadAllTextAsync(temp,Encoding.UTF8,ct);var thread=new Thread(()=>System.Windows.Forms.Clipboard.SetText(text));thread.SetApartmentState(ApartmentState.STA);thread.Start();thread.Join();File.Delete(temp);}
                else File.Move(temp,destination);await stream.WriteAsync(new byte[]{1},ct);
            }
            if(await ReadInt32(stream,ct)!=0x0F7152E2)throw new IOException("Invalid completion marker.");await stream.WriteAsync(new byte[]{1},ct);notify($"Transfer complete — {count} item(s) received ✓");
        }
        catch(Exception ex){try{await stream.WriteAsync(new byte[]{0},CancellationToken.None);}catch{}notify("Receive failed: "+ex.Message);}
        finally{client.Dispose();}
    }

    private bool FixedToken(string supplied){var a=Encoding.UTF8.GetBytes(token);var b=Encoding.UTF8.GetBytes(supplied);return a.Length==b.Length&&CryptographicOperations.FixedTimeEquals(a,b);}
    private static async Task<byte[]> ReadExact(Stream s,int count,CancellationToken ct){var data=new byte[count];var offset=0;while(offset<count){var read=await s.ReadAsync(data.AsMemory(offset,count-offset),ct);if(read<=0)throw new EndOfStreamException();offset+=read;}return data;}
    private static async Task<int> ReadInt32(Stream s,CancellationToken ct)=>BinaryPrimitives.ReadInt32BigEndian(await ReadExact(s,4,ct));
    private static async Task<long> ReadInt64(Stream s,CancellationToken ct)=>BinaryPrimitives.ReadInt64BigEndian(await ReadExact(s,8,ct));
    private static async Task<string> ReadUtf8(Stream s,int max,CancellationToken ct){var length=await ReadInt32(s,ct);if(length<0||length>max)throw new IOException("Invalid metadata length.");return length==0?"":Encoding.UTF8.GetString(await ReadExact(s,length,ct));}
    private static string SafeDestination(string root,string relative,string name){name=SafePart(name,"file.bin");var parts=string.IsNullOrWhiteSpace(relative)?[name]:relative.Replace('\\','/').Trim('/').Split('/').Select(p=>p is "" or "." or ".."?throw new IOException("Unsafe relative path."):SafePart(p,"item")).ToArray();var destination=Path.GetFullPath(Path.Combine([root,..parts]));var prefix=Path.GetFullPath(root+Path.DirectorySeparatorChar);if(!destination.StartsWith(prefix,StringComparison.OrdinalIgnoreCase))throw new IOException("Unsafe destination path.");return destination;}
    private static string SafePart(string value,string fallback){if(string.IsNullOrWhiteSpace(value))value=fallback;foreach(var c in Path.GetInvalidFileNameChars())value=value.Replace(c,'_');value=value.Trim().TrimEnd('.');if(string.IsNullOrWhiteSpace(value))return fallback;return value.Length>180?value[..180]:value;}
    private static string Unique(string path){if(!File.Exists(path))return path;var dir=Path.GetDirectoryName(path)!;var stem=Path.GetFileNameWithoutExtension(path);var ext=Path.GetExtension(path);for(var i=1;i<10000;i++){var candidate=Path.Combine(dir,$"{stem} ({i}){ext}");if(!File.Exists(candidate))return candidate;}throw new IOException("Could not choose a unique filename.");}
    public void Dispose(){stop.Cancel();udp?.Dispose();listener?.Stop();try{Task.WaitAll([discoveryTask??Task.CompletedTask,receiveTask??Task.CompletedTask],1000);}catch{}stop.Dispose();}
}
