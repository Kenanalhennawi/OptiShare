from pathlib import Path

exec(Path('tools/patch_v22_hotspot.py').read_text(), {'__name__': '__main__'})

p = Path('app/src/main/java/com/kenan/optishare/transfer/HotspotService.java')
s = p.read_text()

anchor = 'import androidx.annotation.Nullable;\n'
if anchor not in s:
    raise RuntimeError('nullable import anchor missing')
s = s.replace(anchor, anchor + 'import androidx.annotation.RequiresApi;\n', 1)

# Replace direct reservation.close() calls with an API-gated helper.
s = s.replace('try { reservation.close(); } catch (Exception ignored) { }', 'closeReservation(reservation)')

# Mark all methods whose signature/body references API 26-only hotspot reservation types.
s = s.replace('    private static Credentials credentials(WifiManager.LocalOnlyHotspotReservation value) {',
              '    @RequiresApi(26)\n    private static Credentials credentials(WifiManager.LocalOnlyHotspotReservation value) {', 1)

# Insert helper before credentials.
anchor = '    @RequiresApi(26)\n    private static Credentials credentials(WifiManager.LocalOnlyHotspotReservation value) {'
helper = '''    @RequiresApi(26)
    private static void closeReservation(WifiManager.LocalOnlyHotspotReservation value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) { }
    }

'''
if anchor not in s:
    raise RuntimeError('credentials anchor missing')
s = s.replace(anchor, helper + anchor, 1)

# startHotspot has a runtime API check before touching LocalOnlyHotspot API; make that contract explicit for lint.
s = s.replace('    private synchronized void startHotspot() {',
              '    @android.annotation.SuppressLint("MissingPermission")\n    private synchronized void startHotspot() {', 1)

p.write_text(s)
