package io.github.hypermix94.vibetv;

/**
 * The greeting over the clock: five lines for each part of the day, a new one each time you come back to Home, each
 * with an emoji that Noto has an animated version of (so it moves, like the party button's disco ball). {name} is the
 * owner's name; with no name set, the ", {name}" part is left out.
 */
final class Greetings {
    private Greetings() {
    }

    /** {line, emoji}. */
    private static final String[][] MORNING = {
            {"Good morning, {name}.", "🌞"},
            {"Rise and stream, {name}.", "☕"},
            {"Five more minutes, {name}?", "🥱"},
            {"Breakfast and a show, {name}?", "🍩"},
            {"Look who's up early, {name}!", "👀"},
    };
    private static final String[][] AFTERNOON = {
            {"Good afternoon, {name}.", "😎"},
            {"Lunch and one episode, {name}?", "🍕"},
            {"Peak couch hours, {name}.", "🦥"},
            {"Nap or Netflix, {name}?", "🤔"},
            {"Snack o'clock, {name}.", "😋"},
    };
    private static final String[][] EVENING = {
            {"Good evening, {name}.", "🌛"},
            {"Showtime, {name}.", "🎬"},
            {"Dinner and a movie, {name}?", "🍜"},
            {"Remote secured, {name}.", "😏"},
            {"Party at yours, {name}?", "🥳"},
    };
    private static final String[][] LATE = {
            {"Late show, {name}.", "🍿"},
            {"Still up, {name}?", "👀"},
            {"Night owl mode, {name}.", "🦉"},
            {"Just one more episode, {name}?", "🤞"},
            {"Shh, everyone's asleep, {name}.", "🤫"},
    };

    /** The line and its emoji for this hour: {text, emoji}. {@code which} is any number; it picks one of the five. */
    static String[] pick(int hour, int which, String name) {
        String[][] set = hour < 5 || hour >= 22 ? LATE : hour < 12 ? MORNING : hour < 17 ? AFTERNOON : EVENING;
        String[] g = set[Math.floorMod(which, set.length)];
        String line = name.isEmpty() ? g[0].replace(", {name}", "") : g[0].replace("{name}", name);
        return new String[]{line, g[1]};
    }
}
