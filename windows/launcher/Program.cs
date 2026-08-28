using System.Drawing;
using System.Windows.Forms;

namespace OptiShare.Windows;

internal static class Program
{
    [STAThread] private static void Main() { ApplicationConfiguration.Initialize(); Application.Run(new CompanionForm()); }
}

internal sealed class CompanionForm : Form
{
    private readonly List<string> files = [];
    private readonly ListBox queue = new(); private readonly ComboBox devices = new();
    private readonly Label receiverBadge = new(), status = new(); private readonly ProgressBar progress = new();
    private readonly Button send, add, clear, clipboard, benchmark; private readonly NotifyIcon tray = new();
    private readonly AndroidDiscovery discovery = new(); private readonly PcReceiver receiver; private bool allowExit;

    public CompanionForm()
    {
        Text="OptiShare Windows Companion"; StartPosition=FormStartPosition.CenterScreen; ClientSize=new Size(800,650); MinimumSize=new Size(720,580);
        BackColor=Color.FromArgb(7,26,50); ForeColor=Color.White; Font=new Font("Segoe UI",10f); AllowDrop=true;
        Controls.Add(NewLabel("OptiShare Windows Companion",26,20,24f,Color.White));
        Controls.Add(NewLabel("Send and receive automatically over your local network — no PowerShell or browser address.",30,65,10f,Color.FromArgb(170,205,230)));
        receiverBadge.Text="PC receiver: starting…"; receiverBadge.SetBounds(30,94,735,24); receiverBadge.ForeColor=Color.FromArgb(90,220,165); Controls.Add(receiverBadge);
        Controls.Add(NewLabel("Nearby Android devices",30,125,10f,Color.White));
        devices.SetBounds(30,151,700,34); devices.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right; devices.DropDownStyle=ComboBoxStyle.DropDownList; Controls.Add(devices);
        add=NewButton("Add files",30,202,120,38); clear=NewButton("Clear",160,202,90,38); clipboard=NewButton("Send clipboard",260,202,140,38); benchmark=NewButton("Speed test",410,202,115,38);
        var open=NewButton("Received files",535,202,195,38); Controls.AddRange([add,clear,clipboard,benchmark,open]);
        queue.SetBounds(30,255,700,225); queue.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right; queue.BackColor=Color.FromArgb(13,40,68); queue.ForeColor=Color.White; queue.BorderStyle=BorderStyle.FixedSingle; Controls.Add(queue);
        status.Text="Searching for Android devices…"; status.SetBounds(30,495,700,28); status.Anchor=AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right; status.ForeColor=Color.FromArgb(160,205,235); Controls.Add(status);
        progress.SetBounds(30,526,700,17); progress.Anchor=AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right; Controls.Add(progress);
        send=NewButton("Send selected files to Android",30,557,700,50); send.Anchor=AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right; send.Font=new Font("Segoe UI Semibold",11f); Controls.Add(send);

        receiver=new PcReceiver(AskToAccept,NotifyReceived); discovery.DeviceFound+=DeviceFound;
        add.Click+=(_,_)=>AddFiles(); clear.Click+=(_,_)=>{files.Clear();queue.Items.Clear();progress.Value=0;status.Text="Queue cleared";};
        clipboard.Click+=async(_,_)=>await SendClipboardAsync(); benchmark.Click+=async(_,_)=>await BenchmarkAsync(); send.Click+=async(_,_)=>await SendFilesAsync(); open.Click+=(_,_)=>OpenDownloads();
        DragEnter+=(_,e)=>{if(e.Data?.GetDataPresent(DataFormats.FileDrop)==true)e.Effect=DragDropEffects.Copy;}; DragDrop+=(_,e)=>AddPaths((string[]?)e.Data?.GetData(DataFormats.FileDrop)??[]);
        var menu=new ContextMenuStrip(); menu.Items.Add("Show OptiShare",null,(_,_)=>ShowFromTray()); menu.Items.Add("Exit",null,(_,_)=>{allowExit=true;Close();});
        tray.Text="OptiShare — ready to receive"; tray.Icon=SystemIcons.Information; tray.ContextMenuStrip=menu; tray.Visible=true; tray.DoubleClick+=(_,_)=>ShowFromTray();
        Resize+=(_,_)=>{if(WindowState==FormWindowState.Minimized)Hide();}; FormClosing+=(_,e)=>{if(!allowExit){e.Cancel=true;Hide();}};
        FormClosed+=(_,_)=>{tray.Visible=false;discovery.Dispose();receiver.Dispose();}; Shown+=async(_,_)=>await StartServicesAsync();
    }

    private async Task StartServicesAsync(){try{await receiver.StartAsync();receiverBadge.Text="PC receiver: ready — Android discovers this PC automatically ✓";}catch(Exception ex){receiverBadge.Text="PC receiver unavailable: "+ex.Message;receiverBadge.ForeColor=Color.FromArgb(255,130,130);}discovery.Start();}
    private void DeviceFound(AndroidDevice device)=>Ui(()=>{for(var i=0;i<devices.Items.Count;i++)if(devices.Items[i] is AndroidDevice old&&old.Key==device.Key){devices.Items[i]=device;return;}devices.Items.Add(device);if(devices.SelectedIndex<0)devices.SelectedIndex=0;status.Text=$"Found {device.Name} automatically ✓";});
    private AndroidDevice SelectedDevice()=>devices.SelectedItem as AndroidDevice??throw new InvalidOperationException("On Android, open Receive → Browser receive and keep it active. The phone will appear automatically.");
    private async Task SendFilesAsync(){try{if(files.Count==0)throw new InvalidOperationException("Add at least one file first.");var device=SelectedDevice();SetBusy(true);progress.Value=0;for(var i=0;i<files.Count;i++){var index=i;var path=files[i];var file=new FileInfo(path);status.Text=$"Waiting for approval on {device.Name}: {file.Name}";await AndroidClient.UploadAsync(device,path,(done,total)=>Ui(()=>{var part=total==0?1d:(double)done/total;progress.Value=Math.Clamp((int)(((index+part)/files.Count)*100),0,100);status.Text=$"Sending {file.Name} — {FormatBytes(done)} / {FormatBytes(total)}";}));}progress.Value=100;status.Text=$"Complete — {files.Count} file(s) sent to {device.Name} ✓";MessageBox.Show(this,status.Text,"OptiShare",MessageBoxButtons.OK,MessageBoxIcon.Information);}catch(Exception ex){Fail("OptiShare transfer failed",ex);}finally{SetBusy(false);}}
    private async Task SendClipboardAsync(){try{var text=Clipboard.ContainsText()?Clipboard.GetText():"";if(string.IsNullOrEmpty(text))throw new InvalidOperationException("Windows clipboard has no text to send.");SetBusy(true);var device=SelectedDevice();status.Text=$"Waiting for clipboard approval on {device.Name}…";await AndroidClient.SendClipboardAsync(device,text);status.Text=$"Clipboard sent to {device.Name} ✓";}catch(Exception ex){Fail("OptiShare clipboard",ex);}finally{SetBusy(false);}}
    private async Task BenchmarkAsync(){try{SetBusy(true);var device=SelectedDevice();status.Text=$"Running real 8 MB LAN test with {device.Name}…";var result=await AndroidClient.BenchmarkAsync(device);status.Text=$"Real LAN test: {result.Speed/1024d/1024d:N1} MB/s • 8 MB in {result.Seconds:N2}s";}catch(Exception ex){Fail("OptiShare speed test",ex);}finally{SetBusy(false);}}
    private bool AskToAccept(string message)
    {
        if (InvokeRequired) return (bool)Invoke(new Func<bool>(() => AskToAccept(message)));
        return MessageBox.Show(this,message,"OptiShare incoming transfer",MessageBoxButtons.YesNo,MessageBoxIcon.Question)==DialogResult.Yes;
    }
    private void NotifyReceived(string message)=>Ui(()=>{status.Text=message;tray.ShowBalloonTip(2500,"OptiShare",message,ToolTipIcon.Info);});
    private void SetBusy(bool busy){send.Enabled=add.Enabled=clear.Enabled=clipboard.Enabled=benchmark.Enabled=!busy;} private void Fail(string title,Exception ex){status.Text="Transfer failed";MessageBox.Show(this,ex.Message,title,MessageBoxButtons.OK,MessageBoxIcon.Error);}
    private void AddFiles(){using var dialog=new OpenFileDialog{Multiselect=true,Title="Choose files to send with OptiShare"};if(dialog.ShowDialog(this)==DialogResult.OK)AddPaths(dialog.FileNames);}
    private void AddPaths(IEnumerable<string> paths){foreach(var path in paths.Where(File.Exists))if(!files.Contains(path,StringComparer.OrdinalIgnoreCase)){files.Add(path);var f=new FileInfo(path);queue.Items.Add($"{f.Name} — {FormatBytes(f.Length)}");}status.Text=$"{files.Count} file(s) queued";}
    private void OpenDownloads(){var path=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),"Downloads","OptiShare");Directory.CreateDirectory(path);System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe",$"\"{path}\""){UseShellExecute=true});}
    private void ShowFromTray(){Show();WindowState=FormWindowState.Normal;Activate();} private void Ui(Action action){if(IsDisposed)return;if(InvokeRequired)BeginInvoke(action);else action();}
    private Button NewButton(string text,int x,int y,int w,int h){var b=new Button{Text=text,Location=new Point(x,y),Size=new Size(w,h),FlatStyle=FlatStyle.Flat,BackColor=Color.FromArgb(25,72,108),ForeColor=Color.White};b.FlatAppearance.BorderSize=0;return b;}
    private static Label NewLabel(string text,int x,int y,float size,Color color)=>new(){Text=text,Location=new Point(x,y),Font=new Font("Segoe UI",size),ForeColor=color,AutoSize=true};
    private static string FormatBytes(long v)=>v>=1L<<30?$"{v/(double)(1L<<30):N2} GB":v>=1L<<20?$"{v/(double)(1L<<20):N2} MB":v>=1L<<10?$"{v/1024d:N1} KB":$"{v} B";
}
