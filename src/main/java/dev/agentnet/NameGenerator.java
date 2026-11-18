package dev.agentnet;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Player-looking names, the way real players pick them (not "AdjectiveNoun2012" for everyone): a word or a made-up
 * handle, in one of many styles: plain lowercase, underscores, xX..Xx, "Its"/"Mr"/"lil" prefixes, "YT"/"MC"/"_" endings,
 * a few letters swapped for numbers, a birth year or a short number. Invented, never a real player's name list.
 */
final class NameGenerator {
    /** Words players build names from. */
    private static final String[] WORDS = {
        "shadow", "frost", "ember", "storm", "pixel", "blaze", "void", "nova", "echo", "drift", "flux", "volt", "nyx", "onyx",
        "raven", "wolf", "fox", "hawk", "viper", "cobra", "lynx", "orca", "panda", "otter", "kitsune", "dragon", "phoenix",
        "ghost", "ninja", "knight", "rogue", "ranger", "pirate", "wizard", "sniper", "hunter", "reaper", "samurai",
        "creeper", "enderman", "slime", "golem", "warden", "piglin", "axolotl", "bee", "llama", "turtle",
        "cookie", "taco", "noodle", "waffle", "pickle", "toast", "mango", "kiwi", "cherry", "peach", "berry", "melon",
        "luna", "sol", "astro", "comet", "orbit", "rocket", "cosmo", "zenith", "aurora", "eclipse", "solar", "lunar",
        "crystal", "diamond", "emerald", "obsidian", "netherite", "redstone", "quartz", "amethyst", "copper", "iron",
        "sky", "cloud", "rain", "snow", "thunder", "breeze", "ocean", "river", "forest", "maple", "willow", "cedar",
        "byte", "glitch", "lag", "ping", "bit", "code", "neon", "cyber", "retro", "turbo", "hyper", "mega", "ultra",
        "sam", "alex", "max", "leo", "finn", "milo", "jake", "ethan", "noah", "liam", "mia", "zoe", "ella", "lily",
        "nate", "kai", "eli", "theo", "jace", "ryan", "luke", "evan", "owen", "toby", "ben", "dan", "tom", "nick",
        "chill", "salty", "sweaty", "lucky", "sleepy", "lazy", "sneaky", "crispy", "spicy", "fuzzy", "wobbly", "zesty",
        "dark", "silent", "toxic", "wild", "lost", "lone", "swift", "true", "pure", "bad", "real", "just", "only"
    };
    /** Made-up handles (no meaning, just sound): built from syllables. */
    private static final String[] SYLLABLES = { "ka", "ze", "ri", "vo", "lu", "mi", "xa", "no", "ty", "ra", "ki", "so", "ve", "ly", "dra", "kor", "zen", "vex", "lyn", "mor", "tal", "rin", "zar", "kel", "dex", "vy", "qu", "fen", "jax", "ro" };
    private static final String[] PREFIX = { "Its", "its", "Mr", "Mrs", "lil", "Lil", "The", "not", "x", "Im", "iam", "Sir", "Dr", "Big", "Captain" };
    private static final String[] SUFFIX = { "YT", "MC", "TV", "HD", "_", "__", "x", "z", "PvP", "Gamer", "Plays", "Craft", "123", "Pro", "God", "Main" };

    private NameGenerator() {}

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String word(ThreadLocalRandom r) {
        if (r.nextInt(5) == 0) { // a made-up handle
            StringBuilder sb = new StringBuilder();
            int n = 2 + r.nextInt(2);
            for (int i = 0; i < n; i++) sb.append(SYLLABLES[r.nextInt(SYLLABLES.length)]);
            return sb.toString();
        }
        return WORDS[r.nextInt(WORDS.length)];
    }

    /** A few letters swapped for numbers, the way some players do (e -> 3, o -> 0, a -> 4, i -> 1, s -> 5). */
    private static String leet(String s, ThreadLocalRandom r) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            char o = c;
            if (r.nextInt(3) == 0) o = switch (Character.toLowerCase(c)) { case 'e' -> '3'; case 'o' -> '0'; case 'a' -> '4'; case 'i' -> '1'; case 's' -> '5'; default -> c; };
            sb.append(o);
        }
        return sb.toString();
    }

    private static String one(ThreadLocalRandom r) {
        String a = word(r), b = word(r);
        String name = switch (r.nextInt(12)) {
            case 0 -> a;                                                   // shadow
            case 1 -> cap(a) + cap(b);                                     // ShadowWolf
            case 2 -> a + "_" + b;                                         // shadow_wolf
            case 3 -> "xX" + cap(a) + cap(b) + "Xx";                       // xXShadowWolfXx
            case 4 -> PREFIX[r.nextInt(PREFIX.length)] + cap(a);           // ItsShadow
            case 5 -> cap(a) + SUFFIX[r.nextInt(SUFFIX.length)];           // ShadowYT
            case 6 -> a + b;                                               // shadowwolf
            case 7 -> leet(cap(a) + cap(b), r);                            // Sh4dowW0lf
            case 8 -> "_" + a + "_";                                       // _shadow_
            case 9 -> cap(a) + "_" + cap(b);                               // Shadow_Wolf
            case 10 -> a.toUpperCase().substring(0, Math.min(a.length(), 6)) + cap(b); // SHADOWWolf
            default -> cap(a) + "The" + cap(b);                            // ShadowTheWolf
        };
        // Numbers: a birth year, a short number, or none.
        switch (r.nextInt(5)) {
            case 0 -> name += 2005 + r.nextInt(12);
            case 1 -> name += r.nextInt(10, 100);
            case 2 -> name += r.nextInt(1, 10);
            default -> {}
        }
        return name;
    }

    /** Returns a valid (3-16 chars, letters, digits and _), unused name; {@code taken} holds lower-cased names in use. */
    static String next(Set<String> taken) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 1000; attempt++) {
            String name = one(r);
            if (name.length() < 3 || name.length() > 16 || !name.matches("[A-Za-z0-9_]+")) continue;
            if (!taken.contains(name.toLowerCase())) return name;
        }
        return "player" + r.nextInt(100_000, 1_000_000);
    }
}
