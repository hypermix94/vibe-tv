# 🤖 Install vibe-tv with an AI agent

Copy the prompt below into a coding agent that can run commands on your computer (Claude Code, Codex, Gemini CLI,
Cursor and the like), on a Mac or Linux computer on the same network as the TV. The agent sets up what is missing,
builds vibe-tv from this repository, installs it on your TV and makes it your home screen, asking you before
anything you would want a say in.

Before you start, switch on network debugging on the TV:

1. **Settings → System → About**, then press OK on **Android TV OS build** seven times, until it says you are a
   developer. On some TVs this is under **Settings → Device Preferences → About**.
2. **Settings → System → Developer options**, then switch on **USB debugging** and, if it is there, **Network
   debugging** (some TVs call it ADB over network or Wireless debugging).
3. Note the TV's IP address, from **Settings → Network & Internet** and your Wi-Fi network.

Keep the remote close: the first time the computer connects, the TV asks you to allow it.

## The prompt

```text
Install vibe-tv (https://github.com/hypermix94/vibe-tv), an open-source home screen for Android TV and Google TV,
on my TV, and make it my home screen. Work on this computer, over the network, with adb. Follow these steps in
order, and these rules throughout.

RULES
- Ask me before anything I would want a say in, and wait for my answer. Never guess my personal details.
- Never uninstall anything from the TV, never root it, never flash anything. Do not disable any app on the TV.
- Never press buttons on the TV (adb shell input) while I am using it. Before any key press, check what is on
  screen with: adb shell dumpsys activity activities | grep -m1 mFocusedApp
  If an app other than a home screen is in front, ask me first.
- Never put my TV's IP address, my name, my Wi-Fi name, account emails or serial numbers into any file that could
  be shared or committed. local.properties is fine: it stays on this computer.
- If something fails twice in the same way, stop and tell me what happened instead of retrying in a loop.
- Keep me posted in plain words: one short line per step.

STEP 1 - What I have
- Ask me for the TV's IP address, and confirm network debugging is on.
- Check this computer for git, JDK 17 and the Android SDK (platform 34, build-tools 34.0.0, platform-tools).
  If something is missing, tell me what and how you would install it, and install it only after I agree.
  On a Mac the SDK usually lives in ~/Library/Android/sdk; on Linux in ~/Android/Sdk. The command-line tools from
  developer.android.com are enough; Android Studio is not needed.

STEP 2 - Get the code
- git clone https://github.com/hypermix94/vibe-tv.git and cd into it.
- Read README.md (Install, Permissions, Privacy, Limits) before going on.

STEP 3 - Connect
- adb connect <tv ip>:5555. Tell me to look at the TV and allow this computer, then wait for me.
- adb -s <tv ip>:5555 shell getprop ro.build.version.sdk must be 26 or higher (Android 8). If it is lower, stop:
  vibe-tv will not run on this TV.
- Tell me the TV model and Android version you found (getprop ro.product.model, ro.build.version.release).

STEP 4 - Make it mine
Ask me these together, in one message, and say each one is optional and can be changed later in Home settings:
- The name for the greeting on Home.
- My city for the weather, or Automatic.
- Which apps go on the dock. List the apps installed on the TV for me to choose from:
  adb shell pm list packages -3   (plus the TV's own apps I might want, like Live TV)
  Show them by their names where you can tell them, with the package name in brackets.
Write my answers into local.properties in the repository, as README.md shows (vibetv.name, vibetv.weatherPlace,
vibetv.weatherLatitude, vibetv.weatherLongitude, vibetv.dock). Look up the city's latitude and longitude yourself.

STEP 5 - Build and install
- Run: TV_HOST=<tv ip> ./install.sh
  It builds the app, installs it, makes it the screensaver and gives it the permissions README.md lists.
  Before running it, tell me in one line what those permissions are for.
- If the build fails, read the error, fix the setup on this computer (not the app's code), and try once more.

STEP 6 - Make it Home
- Ask me to press Home on the remote and pick vibe-tv, and to choose "Always" if asked.
  If the TV offers no choice, run, after telling me:
  adb shell cmd package set-home-activity io.github.hypermix94.vibetv/.HomeActivity
- Check it worked: adb shell dumpsys activity activities | grep -m1 mFocusedApp should name
  io.github.hypermix94.vibetv. Take a screenshot (adb exec-out screencap -p > vibe-tv-home.png) and tell me what
  is on it, or show it to me if you can.
- Do NOT disable the TV's own home screen. If it keeps coming back, tell me, and explain the choice: leaving it
  as it is, or disabling it with pm disable-user (never uninstall), which I can undo any time with pm enable.

STEP 7 - Hand over
- Tell me in a few lines what I now have: the dock, all apps below it (press Down), hold OK on any app for its
  menu, Home settings from the top-right icon, the music screen when music plays.
- Tell me how to go back to my TV's own home screen: choose it as Home in Settings, or
  adb shell pm enable <its package> if it was disabled (on Google TV: com.google.android.apps.tv.launcherx).
- Remind me to switch Network debugging and USB debugging off again on the TV, and to revoke USB debugging
  authorisations in Developer options, because the debugging port is open to anyone on my network.
  Then run adb disconnect.
```

## If something goes wrong

- **The TV refuses the connection.** Network debugging is off, or switched itself off after a restart. Switch it on
  again and run the prompt from step 3.
- **Home goes back to the TV's own screen.** Press Home and pick vibe-tv again with Always, or ask the agent to set
  it with `cmd package set-home-activity`.
- **An app is missing from the tiles.** vibe-tv lists the apps you installed and the TV's own Live TV, Settings and
  Play Store; a preinstalled app shows once it has been updated from the Play Store.
- **The screensaver shows the TV's own one.** Run `./install.sh` again; it sets vibe-tv as the screensaver.

vibe-tv is a vibe-coded project, built with Claude Opus 5.5. It is shared as it is, under the MIT licence.
