from pathlib import Path

source = Path('tools/patch_v22_clipboard_benchmark_v2.py').read_text(encoding='utf-8')
source = source.replace("$clearButton.Size = New-Object System.Drawing.Size(100,38)",
                        "$clearButton.Size = New-Object Drawing.Size(100,38)")
source = source.replace("$clearButton.Size = New-Object System.Drawing.Size(90,38)",
                        "$clearButton.Size = New-Object Drawing.Size(90,38)")
exec(compile(source, 'patch_v22_clipboard_benchmark_v3_inner.py', 'exec'))
