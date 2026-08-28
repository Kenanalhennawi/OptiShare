using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Collections.Concurrent;

namespace OptiShare.Windows;

internal sealed class PcReceiver(Func<string,bool> approve, Action<string> notify) : IDisposable
{
    private const int DiscoveryPort=49891, TransferPort=49890;
    private static readonly TimeSpan IoTimeout=TimeSpan.FromSeconds(20);
    private static readonly byte[] Magic=Encoding.ASCII.GetBytes("OPTISHARE-PC-1\n");
    private readonly CancellationTokenSource stop=new(); private readonly string token=Convert.ToHexString(RandomNumberGenerator.GetBytes(24)).ToLowerInvariant();
    private UdpClient? udp; private TcpListener? listener; private Task? discoveryTask, receiveTask;
    private readonly SemaphoreSlim clients=new(4,4);
    private readonly ConcurrentDictionary<string,(long Start,int Count)> attempts=new();

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
        while(!ct.IsCancellationRequested) try { var client=await listener!.AcceptTcpClientAsync(ct);var address=((IPEndPoint?)client.Client.RemoteEndPoint)?.Address.ToString()??"";if(!Allow(address)){client.Dispose();continue;}await clients.WaitAsync(ct);_=Task.Run(async()=>{try{await ReceiveClient(client,ct);}finally{clients.Release();}}); }
        catch(OperationCanceledException){return;} catch(SocketException) when(ct.IsCancellationRequested){return;}
    }

    private async Task ReceiveClient(TcpClient client,CancellationToken ct)
    {
        client.NoDelay=true; client.ReceiveBufferSize=4*1024*1024; client.SendBufferSize=4*1024*1024;
        await using var stream=client.GetStream();
        string? activeTemp=null;
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
                EnsureCapacity(root,size);var destination=SafeDestination(root,relative,name);Directory.CreateDirectory(Path.GetDirectoryName(destination)!);destination=Unique(destination);var temp=destination+".optishare-part";activeTemp=temp;if(File.Exists(temp))File.Delete(temp);
                await stream.WriteAsync(new byte[]{1},ct);await using(var output=new FileStream(temp,FileMode.CreateNew,FileAccess.Write,FileShare.None,1024*1024,true))
                using(var sha=IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
                {
                    var remaining=size;var buffer=new byte[1024*1024];while(remaining>0){var read=await ReadSome(stream,buffer.AsMemory(0,(int)Math.Min(buffer.Length,remaining)),ct);if(read<=0)throw new EndOfStreamException("Connection ended before the file was complete.");await output.WriteAsync(buffer.AsMemory(0,read),ct).AsTask().WaitAsync(IoTimeout,ct);sha.AppendData(buffer,0,read);remaining-=read;}
                    await output.FlushAsync(ct);output.Flush(true);var expected=await ReadExact(stream,32,ct);var actual=sha.GetHashAndReset();if(!CryptographicOperations.FixedTimeEquals(actual,expected)){File.Delete(temp);await stream.WriteAsync(new byte[]{0},ct);throw new IOException($"SHA-256 verification failed for {name}");}
                }
                if(mime=="text/plain"&&name.StartsWith("OptiShare Text",StringComparison.Ordinal)&&size<=262144){var text=await File.ReadAllTextAsync(temp,Encoding.UTF8,ct);var thread=new Thread(()=>System.Windows.Forms.Clipboard.SetText(text));thread.SetApartmentState(ApartmentState.STA);thread.Start();thread.Join();File.Delete(temp);}
                else File.Move(temp,destination);activeTemp=null;await stream.WriteAsync(new byte[]{1},ct);
            }
            if(await ReadInt32(stream,ct)!=0x0F7152E2)throw new IOException("Invalid completion marker.");await stream.WriteAsync(new byte[]{1},ct);notify($"Transfer complete — {count} item(s) received ✓");
        }
        catch(Exception ex){if(activeTemp!=null)try{File.Delete(activeTemp);}catch{}try{await stream.WriteAsync(new byte[]{0},CancellationToken.None);}catch{}notify("Receive failed: "+ex.Message);}
        finally{client.Dispose();}
    }

    private bool FixedToken(string supplied){var a=Encoding.UTF8.GetBytes(token);var b=Encoding.UTF8.GetBytes(supplied);return a.Length==b.Length&&CryptographicOperations.FixedTimeEquals(a,b);}
    private static async Task<byte[]> ReadExact(Stream s,int count,CancellationToken ct){var data=new byte[count];var offset=0;while(offset<count){var read=await ReadSome(s,data.AsMemory(offset,count-offset),ct);if(read<=0)throw new EndOfStreamException();offset+=read;}return data;}
    private static Task<int> ReadSome(Stream s,Memory<byte> buffer,CancellationToken ct)=>s.ReadAsync(buffer,ct).AsTask().WaitAsync(IoTimeout,ct);
    private static async Task<int> ReadInt32(Stream s,CancellationToken ct)=>BinaryPrimitives.ReadInt32BigEndian(await ReadExact(s,4,ct));
    private static async Task<long> ReadInt64(Stream s,CancellationToken ct)=>BinaryPrimitives.ReadInt64BigEndian(await ReadExact(s,8,ct));
    private static async Task<string> ReadUtf8(Stream s,int max,CancellationToken ct){var length=await ReadInt32(s,ct);if(length<0||length>max)throw new IOException("Invalid metadata length.");return length==0?"":Encoding.UTF8.GetString(await ReadExact(s,length,ct));}
    private bool Allow(string address){if(string.IsNullOrWhiteSpace(address))return false;var now=Environment.TickCount64;while(true){if(!attempts.TryGetValue(address,out var old)){if(attempts.TryAdd(address,(now,1))){TrimAttempts(now);return true;}continue;}var next=now-old.Start>=10000?(now,1):(old.Start,old.Count+1);if(next.Count>12)return false;if(attempts.TryUpdate(address,next,old))return true;}}
    private void TrimAttempts(long now){foreach(var item in attempts)if(now-item.Value.Start>=10000)attempts.TryRemove(item.Key,out _);if(attempts.Count<=256)return;foreach(var item in attempts.OrderBy(x=>x.Value.Start).Take(attempts.Count-256))attempts.TryRemove(item.Key,out _);}
    private static void EnsureCapacity(string root,long size){const long reserve=256L*1024*1024;var drive=new DriveInfo(Path.GetPathRoot(Path.GetFullPath(root))!);if(size>long.MaxValue-reserve||drive.AvailableFreeSpace<size+reserve)throw new IOException("Not enough free disk space for this file.");}
    private static string SafeDestination(string root,string relative,string name){name=SafePart(name,"file.bin");var parts=string.IsNullOrWhiteSpace(relative)?[name]:relative.Replace('\\','/').Trim('/').Split('/').Select(p=>p is "" or "." or ".."?throw new IOException("Unsafe relative path."):SafePart(p,"item")).ToArray();var destination=Path.GetFullPath(Path.Combine([root,..parts]));var prefix=Path.GetFullPath(root+Path.DirectorySeparatorChar);if(!destination.StartsWith(prefix,StringComparison.OrdinalIgnoreCase))throw new IOException("Unsafe destination path.");return destination;}
    private static string SafePart(string value,string fallback){if(string.IsNullOrWhiteSpace(value))value=fallback;foreach(var c in Path.GetInvalidFileNameChars())value=value.Replace(c,'_');value=value.Trim().TrimEnd('.');if(string.IsNullOrWhiteSpace(value))return fallback;return value.Length>180?value[..180]:value;}
    private static string Unique(string path){if(!File.Exists(path))return path;var dir=Path.GetDirectoryName(path)!;var stem=Path.GetFileNameWithoutExtension(path);var ext=Path.GetExtension(path);for(var i=1;i<10000;i++){var candidate=Path.Combine(dir,$"{stem} ({i}){ext}");if(!File.Exists(candidate))return candidate;}throw new IOException("Could not choose a unique filename.");}
    public void Dispose(){stop.Cancel();udp?.Dispose();listener?.Stop();try{Task.WaitAll([discoveryTask??Task.CompletedTask,receiveTask??Task.CompletedTask],1000);}catch{}clients.Dispose();stop.Dispose();}
}
