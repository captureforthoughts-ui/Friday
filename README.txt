Friday for Android, version 2: works on mobile data, with or without your laptop

WHAT IT DOES
- Phone only: the app talks to Google's voice service directly, over mobile data or any Wi-Fi. Your laptop can be off or far away.
  It can search the web, open Google Maps, work out travel times and nearby places, set timers and alarms, open a calendar event or an
  email draft for you to tap Save/Send, open the dialer, remember things about you, and chat in the same relaxed voice.
- Laptop link: when your laptop is on and reachable, the app uses it instead, so Friday can also control the laptop (click, type, screen,
  Gmail and Calendar, helpers). The app picks by itself ("Auto"), or you can force "Phone only" or "Laptop only" in its settings (gear).

GET THE APP (free, about 5 minutes, no coding)  - same as before
1. github.com: sign in, "+" then "New repository", name it friday-phone, Private, Create.
2. "uploading an existing file": drag EVERYTHING inside this folder in (including the .github folder), Commit changes.
   (If you already have the old friday-phone repository, upload these files over the old ones instead.)
3. Actions tab, enable workflows if asked. "Build Friday APK" starts by itself (about 4 minutes). Wait for the green tick.
4. Open the run, scroll to Artifacts, click Friday-APK, unzip, send app-debug.apk to your phone and install it.
   If you had version 1 installed, uninstall it first.

FIRST-TIME SETUP (once, on your home Wi-Fi, laptop running Friday)
1. Laptop: open Friday's menu, "Phone link...". Note the address (like 192.168.1.23) and the PIN.
2. Phone: open the app. In the settings screen type the address and PIN, then tap "Copy settings from laptop".
   This copies your Google key, your memories and your voice to this phone (only this phone, over the PIN-protected link).
   Or skip it and paste your Google key into the key box.
3. Tap "Save and start". Allow the microphone. Tap the blue orb and talk.

USING IT AWAY FROM HOME
- Phone only works anywhere with signal. Nothing else to do.
- To also reach the laptop from outside (optional): install Tailscale (free, tailscale.com) on the laptop AND the phone, sign in to the
  same account on both. The laptop gets an address starting with 100. (Check Friday.bat shows it). Add it to the app's laptop addresses
  next to the home one, separated by a comma. The laptop must be on, awake, and running Friday. In Windows, if asked, allow Python on
  "Public" networks too, because Tailscale counts as one.

MOBILE DATA
Only your speech is uploaded (silence is not). Roughly 3 MB per minute of active back-and-forth, so about 100 MB is half an hour of
talking. Idle time costs almost nothing. Google's free voice limits are the same as on the laptop.

USING OTHER APPS ("open WhatsApp and message Sam", "scroll down", "tap the search box")  - phone-only mode
Friday can open apps and tap, type and scroll for you. It will NOT touch banking, payment, investing, crypto, password-manager or
authenticator apps, sign-in / PIN / one-time-code screens, or password boxes. That is enforced inside the app, not just asked of the
voice. Anything that sends, buys, pays, deletes or posts is read back to you and only done after you clearly say yes. You can add
more words to avoid (in the app's settings). It is not perfect: it recognises apps and screens by name and wording, so keep your
own judgement and don't ask it to use apps with money in them.
One-time setup in the app's settings screen (do 1 to 4 in order):
  1. "Allow restricted settings": opens Friday's app info. Tap the three dots (top right), then "Allow restricted settings".
     (Android 13 and newer hide Accessibility for apps installed outside the Play Store until you do this.)
  2. "Turn Friday on in Accessibility": find Friday (maybe under "Installed apps"), switch it on, accept the warning.
  3. Xiaomi only: "allow pop-ups in background": turn on "Display pop-up windows while running in the background" and
     "Show on lock screen". Without it Xiaomi stops Friday from opening apps while you are in another app.
  4. "Battery: don't restrict Friday": choose Friday, then "No restrictions" / "Don't optimise".
While Friday is listening, a notification "Friday is listening" shows, and you can keep talking while using other apps. Tap Stop on
the notification to switch the microphone off. If the microphone stops when you leave the app, tell me which phone and Android
version: some phones are strict about background microphones.

GOOD TO KNOW
- Keep the screen on while talking (the app keeps it awake). Locking the phone stops the microphone.
- Phone-only memories live on the phone; they are not copied back to the laptop yet.
- Phone-only Gmail/Calendar reading is not included (that needs the laptop link). Adding an event or writing an email opens your own
  Calendar / Mail app prefilled, and nothing is saved or sent until you tap Save / Send.
- If the build shows a red cross, open the run, copy the red error text, and send it to me.


NEW IN 3.2: SMART HOME AND WAKING YOUR LAPTOP
- Do "Copy settings from laptop" again (home Wi-Fi, Friday running). It also copies your Home Assistant link and your saved devices.
- "Wake my desktop" / "turn on my laptop" sends a Wake-on-LAN signal. It only works when the phone is on the SAME Wi-Fi as that computer.
- "Turn on the bedroom light", "set the aircon to 24", "lock the front door" go straight to Home Assistant from the phone (home Wi-Fi, or Tailscale on both).
- Unlocking, disarming and opening doors or garage doors need your spoken yes.


NEW IN 3.7: FRIDAY CALLS YOU
- Say to Friday on the laptop: "call me", "call me in 10 minutes", "ring me about the meeting". Your phone rings like a call
  with Answer and Decline. Answer, and Friday talks to you on the phone and says why she called. Works on mobile data.
- One-time setup on the phone: open the app's settings (the gear), tap "Copy settings from laptop" (on home Wi-Fi) so it gets the
  call code, or ask Friday "set up phone calls" and type the code into "Call code". Then:
  1) tap "Calls: off" so it says ON and allow notifications,
  2) tap "1. Allow ringing over the lock screen" and switch it on,
  3) tap "2. Let Friday run in the background" and choose Allow,
  4) tap "Test: ring this phone in 5 seconds" and lock the screen.
- On Oppo/ColorOS also: Settings > Battery > Friday > allow background activity and auto-start, and in the recent-apps screen
  lock Friday (pull the card down or tap the lock icon) so the phone does not close it.
- The call code is a long private secret. The reason text passes through ntfy.sh, a free public notification service.


NEW IN 3.8: FRIDAY ON YOUR ANDROID TV / GOOGLE TV
- The same app now installs on an Android TV and shows up on the TV home screen (look for Friday in "Apps").
- Build it the same way as before (upload everything, including banner.png, to the friday-phone GitHub repository; get app-debug.apk).
- Put it on the TV: on the TV open Settings > Device Preferences > About, press OK on "Build" seven times, then Developer options > turn on
  USB debugging (and Network debugging / Wireless debugging if shown). Note the TV's IP address (Settings > Network). On the laptop, in
  the platform-tools folder:  .\adb connect TV-IP:5555   then   .\adb install -r app-debug.apk
  (Accept the "Allow debugging" box on the TV the first time.) Or install the free "Downloader" app on the TV and open a link to the APK.
- Remote control: arrows move, OK taps the orb, the MENU button (or the settings / star button) opens Friday's settings. Typing the laptop
  address and PIN uses the TV's on-screen keyboard.
- Microphone: most TV remotes' microphone cannot be used by apps. Friday on the TV needs a TV with a built-in mic, a USB microphone or a
  Bluetooth headset/mic connected to the TV. Without one the TV can still show Friday and speak, but it cannot hear you.

TV DISPLAY (3.8), ALL WIRELESS: to show the LAPTOP's Friday screen on the TV, turn on "Show Friday's screen on my TV" in the laptop app's
Settings > Startup. Copy app-debug.apk into the Friday folder on the laptop. On the TV install the free "Downloader" app, open it and type the
address the laptop shows (http://LAPTOP-IP:8765/apk?t=CODE). Install the file. Open Friday on the TV and type the laptop address and code
shown in the laptop's settings (once). The laptop listens and talks, so the TV needs no microphone. MENU on the remote changes the address.
(The adb command still works if you prefer it.)
