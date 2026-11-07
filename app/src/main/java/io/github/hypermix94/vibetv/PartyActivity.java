package io.github.hypermix94.vibetv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/**
 * Party mode on demand: the music screen (the screensaver's) opened straight away from the control panel, with party
 * mode on. It closes as the screensaver does: any key but Left, Right and OK.
 */
public class PartyActivity extends Activity {
    private MusicScreen music;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        music = new MusicScreen(this, new MusicScreen.Host() {
            @Override
            public android.view.Window window() {
                return getWindow();
            }

            @Override
            public void close() {
                finish();
            }
        });
        View view = music.create();
        if (view == null) {   // the music stopped meanwhile
            finish();
            return;
        }
        setContentView(view);
        music.startParty();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (music != null) music.resume();
    }

    @Override
    protected void onPause() {
        if (music != null) music.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (music != null) music.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (music != null) return music.dispatchKeyEvent(e);
        return super.dispatchKeyEvent(e);
    }
}
