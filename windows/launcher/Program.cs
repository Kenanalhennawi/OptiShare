using System.Diagnostics;
using System.Drawing;
using System.Reflection;
using System.Windows.Forms;

namespace OptiShare.Windows;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new LauncherForm());
    }
}

internal sealed class LauncherForm : Form
{
    private readonly Label status;
    private readonly Button startButton;
    private readonly string runtimeDirectory;
    private readonly string companionScript;
    private readonly string receiverScript;

    public LauncherForm()
    {
        runtimeDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "OptiShare", "Companion", "2.2");
        companionScript = Path.Combine(runtimeDirectory, "OptiShare-Companion.ps1");
        receiverScript = Path.Combine(runtimeDirectory, "OptiShare-PC-Receiver.ps1");

        Text = "OptiShare Windows Companion";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(560, 330);
        MinimumSize = new Size(560, 330);
        BackColor = Color.FromArgb(7, 24, 43);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);
        MaximizeBox = false;

        var title = new Label
        {
            Text = "OptiShare Windows Companion",
            ForeColor = Color.White,
            Font = new Font("Segoe UI Semibold", 22f),
            AutoSize = true,
            Location = new Point(28, 28)
        };
        Controls.Add(title);

        var subtitle = new Label
        {
            Text = "Private local receive companion for OptiShare 2.2",
            ForeColor = Color.FromArgb(158, 198, 226),
            Font = new Font("Segoe UI", 10.5f),
            AutoSize = true,
            Location = new Point(31, 75)
        };
        Controls.Add(subtitle);

        var card = new Panel
        {
            BackColor = Color.FromArgb(15, 46, 74),
            Location = new Point(28, 112),
            Size = new Size(504, 126)
        };
        Controls.Add(card);

        status = new Label
        {
            Text = "Ready. The receiver is embedded in this EXE and is extracted locally when started.",
            ForeColor = Color.FromArgb(202, 224, 239),
            AutoSize = false,
            Size = new Size(458, 50),
            Location = new Point(22, 18)
        };
        card.Controls.Add(status);

        startButton = new Button
        {
            Text = "Start receiving",
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(34, 145, 238),
            ForeColor = Color.White,
            Location = new Point(22, 76),
            Size = new Size(220, 38)
        };
        startButton.FlatAppearance.BorderSize = 0;
        startButton.Click += (_, _) => StartCompanion();
        card.Controls.Add(startButton);

        var openFolderButton = new Button
        {
            Text = "Open received files",
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(25, 72, 108),
            ForeColor = Color.White,
            Location = new Point(258, 76),
            Size = new Size(220, 38)
        };
        openFolderButton.FlatAppearance.BorderSize = 0;
        openFolderButton.Click += (_, _) => OpenReceivedFolder();
        card.Controls.Add(openFolderButton);

        var note = new Label
        {
            Text = "No Internet or account required. Windows may show a firewall prompt the first time the receiver opens.",
            ForeColor = Color.FromArgb(123, 165, 195),
            AutoSize = false,
            Size = new Size(500, 48),
            Location = new Point(31, 255)
        };
        Controls.Add(note);
    }

    private void StartCompanion()
    {
        try
        {
            ExtractEmbeddedScripts();
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoLogo -NoProfile -ExecutionPolicy Bypass -File \"{companionScript}\"",
                WorkingDirectory = runtimeDirectory,
                UseShellExecute = true
            };
            Process.Start(psi);
            status.Text = "Companion started. Keep its receiver window open while sending from Android.";
            startButton.Text = "Start another receiver";
        }
        catch (Exception ex)
        {
            status.Text = "Could not start the companion.";
            MessageBox.Show(ex.Message, "OptiShare launch error", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void ExtractEmbeddedScripts()
    {
        Directory.CreateDirectory(runtimeDirectory);
        ExtractResource("OptiShare-Companion.ps1", companionScript);
        ExtractResource("OptiShare-PC-Receiver.ps1", receiverScript);
    }

    private static void ExtractResource(string resourceName, string destination)
    {
        using Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName)
            ?? throw new InvalidOperationException($"Embedded resource missing: {resourceName}");
        using FileStream target = new(destination, FileMode.Create, FileAccess.Write, FileShare.Read);
        source.CopyTo(target);
    }

    private void OpenReceivedFolder()
    {
        var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "OptiShare");
        try
        {
            Directory.CreateDirectory(path);
            Process.Start(new ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = $"\"{path}\"",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "Could not open received files", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}
