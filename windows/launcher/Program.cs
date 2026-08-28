using System.Diagnostics;
using System.Drawing;
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
    private readonly Button openFolderButton;

    public LauncherForm()
    {
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
            Text = "Ready. Keep this launcher next to the OptiShare Companion scripts.",
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

        openFolderButton = new Button
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

    private static string BaseDirectory => AppContext.BaseDirectory;
    private static string CompanionScript => Path.Combine(BaseDirectory, "OptiShare-Companion.ps1");
    private static string ReceiverScript => Path.Combine(BaseDirectory, "OptiShare-PC-Receiver.ps1");

    private void StartCompanion()
    {
        if (!File.Exists(CompanionScript) || !File.Exists(ReceiverScript))
        {
            MessageBox.Show(
                "The launcher must stay in the same folder as OptiShare-Companion.ps1 and OptiShare-PC-Receiver.ps1.",
                "OptiShare files missing",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            status.Text = "Companion scripts are missing from this folder.";
            return;
        }

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoLogo -NoProfile -ExecutionPolicy Bypass -File \"{CompanionScript}\"",
                WorkingDirectory = BaseDirectory,
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
