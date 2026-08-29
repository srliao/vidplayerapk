# Acceptance checklist

Run on a real Fire tablet. Record the build (git SHA) and the date.

| # | Test | Expected | Pass |
|---|---|---|---|
| 1 | Fresh install, no streams | Setup screen opens automatically | |
| 2 | Paste a valid RTSPS URL with a name, Add, tap the row | Video within 10 s; name shows in the bar | |
| 3 | Paste a malformed URL, Add | Inline error, no crash, nothing added | |
| 4 | Add a well-formed but unreachable URL, tap it | Overlay shows failure and a climbing retry count; no hang | |
| 5 | Add three streams; press Next repeatedly | Cycles in list order and wraps | |
| 6 | Delete the currently-playing stream | Falls back to the first remaining; no crash | |
| 7 | Reboot the tablet | Last-selected stream returns with no interaction | |
| 8 | Disable Wi-Fi 60 s, re-enable | Video returns within 30 s | |
| 9 | Reboot the camera/NVR | Video returns within 60 s | |
| 10 | **Block the camera's IP at the firewall, leaving Wi-Fi up** | Watchdog fires within ~30 s; overlay appears; recovers when unblocked | |
| 11 | Leave running 72 hours | Still live, no memory growth, no frozen frame | |
| 12 | Screen off via power button, then on | Video resumes, no black surface | |
| 13 | Open Diag, `curl http://<ip>:8080/stats` | JSON returns; URL is masked | |
| 14 | Close Diag, curl again | Connection refused | |
| 15 | Install a newer CI build over an existing one | Installs as an update; stream list survives | |
| 16 | Press Receive with Wi-Fi up | Panel shows the tablet's real IP and the `uv run` command | |
| 17 | Push a stream from a computer while other streams exist | Row appears; playback does **not** switch | |
| 18 | Push a second stream without re-pressing Receive | Second row appears; status still says "still listening" | |
| 19 | Fresh install, push the first stream | It starts playing behind the Setup screen (`Kiosk.add`'s `wasEmpty` path) | |
| 20 | Push `http://nope` | Script prints the validator's reason and re-prompts; nothing added | |
| 21 | Press Cancel, then push again | Connection refused; no socket outlives the panel | |
| 22 | Reboot after pushing | Pushed streams survive; same persistence as Add | |

**Test 10 is the one that matters.** It is the only test exercising the silent
stall path, and that is the failure mode that actually occurs in the field. If
everything else passes and 10 fails, the build is not done.

**Test 11** catches `Media` leaks. Poll `/stats` across at least a dozen
reconnect cycles and watch `reconnectCount` climb while memory stays flat. It
also settles whether `vlcTimeMs` ever advances on this hardware — if it stays
at 0, the original spec's `player.time` watchdog would never have worked.
