using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace OptiShare.Windows;

internal sealed record AndroidDevice(string Name, IPAddress Address, int Port, string Token, int ProtocolVersion, int SecurePort)
{
    public string Key => $"{Address}:{Port}";
    public override string ToString() => $"{Name} — {Address}";
}

internal sealed class AndroidDiscovery : IDisposable
{
    private const int Port = 49894;
    private readonly CancellationTokenSource stop = new();
    private Task? worker;
    public event Action<AndroidDevice>? DeviceFound;

    public void Start() { worker ??= Task.Run(() => RunAsync(stop.Token)); }

    private async Task RunAsync(CancellationToken token)
    {
        using var udp = new UdpClient(0) { EnableBroadcast = true };
        var payload = Encoding.UTF8.GetBytes("OPTISHARE_ANDROID_DISCOVER_V1");
        while (!token.IsCancellationRequested)
        {
            foreach (var endpoint in BroadcastEndpoints())
                try { await udp.SendAsync(payload, endpoint, token); } catch { }
            var until = DateTime.UtcNow.AddSeconds(2);
            while (DateTime.UtcNow < until && !token.IsCancellationRequested)
            {
                try
                {
                    var receive = udp.ReceiveAsync(token).AsTask();
                    var completed = await Task.WhenAny(receive, Task.Delay(250, token));
                    if (completed != receive) continue;
                    var packet = await receive;
                    var parts = Encoding.UTF8.GetString(packet.Buffer).Trim().Split('|');
                    if ((parts.Length != 5 && parts.Length != 6) || parts[0] != "OPTISHARE_ANDROID_V1"
                        || !int.TryParse(parts[2], out var port) || port is < 1 or > 65535
                        || parts[3].Length is < 16 or > 128) continue;
                    if(!int.TryParse(parts[4],out var version)||version is <1 or >2)continue;
                    var securePort=0;if(version>=2&&(parts.Length!=6||!int.TryParse(parts[5],out securePort)||securePort is <1024 or >65535))continue;
                    DeviceFound?.Invoke(new AndroidDevice(parts[1], packet.RemoteEndPoint.Address, port, parts[3],version,securePort));
                }
                catch (OperationCanceledException) { return; }
                catch (SocketException) { }
            }
        }
    }

    private static IEnumerable<IPEndPoint> BroadcastEndpoints()
    {
        yield return new IPEndPoint(IPAddress.Broadcast, Port);
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up) continue;
            foreach (var item in adapter.GetIPProperties().UnicastAddresses)
            {
                if (item.Address.AddressFamily != AddressFamily.InterNetwork || item.IPv4Mask == null) continue;
                var ip = item.Address.GetAddressBytes(); var mask = item.IPv4Mask.GetAddressBytes(); var broadcast = new byte[4];
                for (var i = 0; i < 4; i++) broadcast[i] = (byte)(ip[i] | ~mask[i]);
                yield return new IPEndPoint(new IPAddress(broadcast), Port);
            }
        }
    }

    public void Dispose() { stop.Cancel(); try { worker?.Wait(1000); } catch { } stop.Dispose(); }
}
