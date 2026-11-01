package io.github.hypermix94.vibetv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * A search pill: a magnifier, the words, and a mic. The screen showing it moves the remote's focus onto the field or
 * the mic ({@link #focus}); OK on the field brings up the on-screen keyboard, OK on the mic listens for an app's
 * name through the TV's speech service. Every change of the words goes to {@code onQuery}.
 */
final class SearchBar extends LinearLayout {
    private final float s;
    private final EditText field;
    private final FrameLayout mic;
    private final Consumer<String> onQuery;
    private final Runnable onDone;
    private SpeechRecognizer recognizer;
    private boolean typing, listening;
    private int focused = -1;   // -1 none, 0 the field, 1 the mic

    SearchBar(Context c, float s, Consumer<String> onQuery, Runnable onDone) {
        super(c);
        this.s = s;
        this.onQuery = onQuery;
        this.onDone = onDone;
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(d(24), 0, d(8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(d(34));
        setBackground(bg);
        ImageView glass = new ImageView(c);
        glass.setImageResource(R.drawable.ic_search);
        glass.setAlpha(0.8f);
        addView(glass, new LayoutParams(d(30), d(30)));
        field = new EditText(c);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0x99FFFFFF);
        field.setHint("Search apps");
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, d(28));
        field.setTypeface(c.getResources().getFont(R.font.outfit_regular));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setFocusable(false);   // the remote drives the screen until OK is pressed on the field
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence t, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                onQuery.accept(e.toString());
            }
        });
        field.setOnEditorActionListener((v, action, ev) -> {
            stopTyping();
            return true;
        });
        LayoutParams fLp = new LayoutParams(0, -2, 1f);
        fLp.leftMargin = d(14);
        addView(field, fLp);
        mic = new FrameLayout(c);
        View disc = new View(c);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        disc.setBackground(circle);
        disc.setAlpha(0f);
        mic.addView(disc, new FrameLayout.LayoutParams(-1, -1));
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(R.drawable.ic_mic);
        mic.addView(glyph, new FrameLayout.LayoutParams(d(30), d(30), Gravity.CENTER));
        mic.setContentDescription("Search by voice");
        addView(mic, new LayoutParams(d(54), d(54)));
    }

    boolean typing() {
        return typing;
    }

    String query() {
        return field.getText().toString();
    }

    void clear() {
        field.setText("");
    }

    /** Where the remote is: -1 elsewhere, 0 on the field, 1 on the mic. */
    void focus(int which) {
        focused = which;
        paint();
    }

    /** OK: type on the field, or listen on the mic. */
    void press() {
        if (focused == 0) startTyping();
        else if (focused == 1) listen();
    }

    private void paint() {
        boolean onField = focused == 0 || typing, onMic = focused == 1 || listening;
        GradientDrawable bg = (GradientDrawable) getBackground();
        bg.setStroke(d(3), onField ? Color.WHITE : 0x00FFFFFF);
        bg.setColor(onField ? 0x40FFFFFF : 0x26FFFFFF);
        mic.getChildAt(0).animate().alpha(onMic ? 1f : 0f).setDuration(400).start();
        ((ImageView) mic.getChildAt(1)).setColorFilter(onMic ? Color.BLACK : Color.WHITE);
        animate().scaleX(focused >= 0 ? 1.03f : 1f).scaleY(focused >= 0 ? 1.03f : 1f).setDuration(400).start();
    }

    private void startTyping() {
        typing = true;
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        field.requestFocus();
        getContext().getSystemService(InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        paint();
    }

    /** Back or Search on the keyboard: done typing; the screen takes the remote again. */
    void stopTyping() {
        typing = false;
        getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
        field.setFocusable(false);
        paint();
        onDone.run();
    }

    private void listen() {
        if (listening) {
            if (recognizer != null) recognizer.stopListening();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            field.setHint("Voice search is not available on this TV");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle b) {
                    field.setHint("Listening…");
                }

                @Override
                public void onBeginningOfSpeech() {
                }

                @Override
                public void onRmsChanged(float db) {
                    float k = 1f + Math.max(0f, Math.min(1f, (db + 2f) / 12f)) * 0.18f;   // the mic grows with the voice
                    mic.setScaleX(k);
                    mic.setScaleY(k);
                }

                @Override
                public void onBufferReceived(byte[] b) {
                }

                @Override
                public void onEndOfSpeech() {
                }

                @Override
                public void onError(int error) {
                    doneListening();
                    field.setHint(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ? "Didn't catch that. Try again" : "Voice search is not working right now");
                }

                @Override
                public void onResults(Bundle b) {
                    doneListening();
                    ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (said != null && !said.isEmpty()) field.setText(said.get(0));
                    onDone.run();
                }

                @Override
                public void onPartialResults(Bundle b) {
                    ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (said != null && !said.isEmpty() && !said.get(0).isEmpty()) field.setText(said.get(0));
                }

                @Override
                public void onEvent(int type, Bundle b) {
                }
            });
        }
        listening = true;
        field.setText("");
        paint();
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3));
    }

    /** The remote's voice button: straight to listening. */
    void voice() {
        focus(1);
        listen();
    }

    private void doneListening() {
        listening = false;
        field.setHint("Search apps");
        mic.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
        paint();
    }

    void release() {
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        listening = false;
    }

    private int d(float designPx) {
        return Cards.d(s, designPx);
    }
}
