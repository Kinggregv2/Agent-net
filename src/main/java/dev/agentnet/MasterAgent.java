package dev.agentnet;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.agentnet.role.Role;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The master agent: everything you type in its chat goes through here.
 * <ol>
 *   <li>"/agent ..." (or any /command) runs as typed.</li>
 *   <li>Plain sentences it understands itself ("get a new farmer", "get 3 fishers",
 *       "make Bob a miner", "remove 2 agents", "load everyone") become /agent commands.</li>
 *   <li>Anything else goes to a small local LLM (Ollama) when one is running; it answers
 *       with /agent commands, which run, and a short reply. Settings: agentnet/master.txt.</li>
 * </ol>
 * Main thread, except the LLM call (background, result handed back to the main thread).
 */
final class MasterAgent {
    /** Chat lines shown in the master screen, oldest first. Main thread. */
    static final List<String> LOG = new ArrayList<>();
    private static final int MAX_LOG = 300;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static volatile boolean thinking;

    private MasterAgent() {}

    static boolean thinking() { return thinking; }

    static void say(String line) {
        LOG.add(line);
        while (LOG.size() > MAX_LOG) LOG.remove(0);
    }

    /** An agent event worth showing in the master chat (joins, warnings...). Any thread. */
    static void event(String line) {
        Minecraft.getInstance().execute(() -> say(line));
    }

    /** Handle one line you typed. Main thread. */
    static void input(String text) {
        text = text.strip();
        if (text.isEmpty()) return;
        say("§fYou: " + text);
        if (text.startsWith("/")) { run(text); return; }
        // The autopilot, in plain words: "run the base yourself", "take over", "autopilot off", "how is the civ doing".
        String low = text.toLowerCase(java.util.Locale.ROOT);
        if (low.matches(".*\\b(autopilot|civ)\\b.*\\b(off|stop)\\b.*|.*\\bstop (running|managing)\\b.*")) { run("agent civ off"); return; }
        if (low.matches(".*\\b(autopilot|civ)\\b.*\\bon\\b.*|.*\\b(run|manage|take over|grow)\\b.*\\b(civ|base|settlement|colony|village|everything|it yourself|yourself)\\b.*|^take over.*")) {
            run("agent civ on");
            return;
        }
        if (low.matches(".*\\b(status|how is|how are|report)\\b.*\\b(civ|base|settlement|colony|crew|things)\\b.*")) { say("§bCiv: §f" + Civ.summary()); return; }
        List<String> cmds = understand(text);
        if (!cmds.isEmpty()) {
            for (String c : cmds) run(c);
            return;
        }
        askLlm(text);
    }

    // ------------------------------------------------------------------ built-in understanding

    private static final Map<String, Integer> NUMBERS = Map.ofEntries(Map.entry("a", 1), Map.entry("an", 1),
            Map.entry("one", 1), Map.entry("another", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("some", 2), Map.entry("few", 3), Map.entry("couple", 2));

    private static final Pattern SPAWN = Pattern.compile(
            "^(?:please\\s+)?(?:get|spawn|add|hire|bring|recruit|i need|we need|give me|make)\\s+(?:me\\s+|us\\s+)?(?:a\\s+couple\\s+of\\s+|a\\s+few\\s+)?(\\d+|[a-z]+)?\\s*(?:new\\s+|more\\s+)?([a-z]+?)s?$");
    private static final Pattern ASSIGN = Pattern.compile("^(?:make|turn|set)\\s+([A-Za-z0-9_]{3,16})\\s+(?:into\\s+|to\\s+)?(?:a|an)?\\s*([a-z]+)$");
    private static final Pattern REMOVE = Pattern.compile("^(?:remove|kick|fire|dismiss|send away)\\s+(all|everyone|\\d+|[A-Za-z0-9_]{3,16})(?:\\s+agents?)?$");

    /** "Bob mine 10 iron", "make all mine coal", "tell Ann to mine 5 diamonds". */
    private static final Pattern MINE = Pattern.compile(
            "^(?i)(?:tell |have |let |make |get )?([A-Za-z0-9_]{3,16}) (?:to )?(?:go )?mine (?:me )?(\\d+ )?([a-z_ ]+?)$");

    /** "iron" -> iron_ore, "diamonds" -> diamond_ore, "wood" -> logs, "stone" stays; null if unknown. */
    private static String mineBlock(String w) {
        w = w.strip().replace(' ', '_');
        if (w.endsWith("s") && !w.equals("logs") && !w.endsWith("debris")) w = w.substring(0, w.length() - 1);
        if (w.matches("wood|log|tree|logs")) return "logs";
        if (w.matches("iron|coal|copper|gold|redstone|lapis|diamond|emerald")) return w + "_ore";
        if (w.equals("netherite") || w.equals("debri")) return "ancient_debris";
        if (w.matches("cobblestone|cobble")) return "stone";
        if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.Identifier.withDefaultNamespace(w))) return w;
        return null;
    }

    /** Commands for sentences the master understands without an LLM; empty if none. */
    static List<String> understand(String text) {
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "").strip();
        List<String> out = new ArrayList<>();
        Matcher m = SPAWN.matcher(t);
        if (m.matches()) {
            Role role = roleFrom(m.group(2));
            if (role != null) {
                int n = count(m.group(1));
                out.add("/agent spawn " + n + " " + role.id());
                return out;
            }
        }
        m = MINE.matcher(text.strip().replaceAll("[.!?]+$", ""));
        if (m.matches()) {
            String who = m.group(1).equalsIgnoreCase("everyone") || m.group(1).equalsIgnoreCase("everybody") ? "all" : m.group(1);
            String block = mineBlock(m.group(3).toLowerCase(Locale.ROOT));
            if (block != null) { out.add("/agent mine " + who + " " + block + " " + (m.group(2) != null ? m.group(2).strip() : "16")); return out; }
        }
        m = ASSIGN.matcher(text.strip().replaceAll("[.!?]+$", ""));
        if (m.matches()) {
            Role role = roleFrom(m.group(2).toLowerCase(Locale.ROOT));
            if (role != null) { out.add("/agent role " + m.group(1) + " " + role.id()); return out; }
        }
        if (t.matches("(?:send|tell) (?:everyone|everybody|all(?: agents)?|them all) (?:home|away|off)(?:,.*)?|(?:that's all|we're done)(?:,.*)?")) {
            out.add("/agent leave all");
            return out;
        }
        m = REMOVE.matcher(t);
        if (m.matches()) {
            String who = m.group(1).equals("everyone") ? "all" : m.group(1);
            out.add("/agent leave " + who);
            return out;
        }
        if (t.matches("(?:list|who(?:'s| is)? (?:online|working|here)|show (?:me )?(?:the )?agents|status)")) out.add("/agent list");
        else if (t.matches("(?:load|bring back|restore)(?: (?:everyone|all|the crew|my agents|agents))?")) out.add("/load");
        else if (t.matches("(?:war|go to war|start (?:a )?war|prepare for war)")) out.add("/agent war prepare");
        else if (t.matches("(?:peace|end (?:the )?war|stop (?:the )?war)")) out.add("/agent war off");
        return out;
    }

    private static int count(String word) {
        if (word == null || word.isEmpty()) return 1;
        if (word.chars().allMatch(Character::isDigit)) return Math.max(1, Math.min(50, Integer.parseInt(word)));
        return NUMBERS.getOrDefault(word, 1);
    }

    /** "farmer", "farmers", "fisherman", "fishermen", "guard"... -> role. */
    static Role roleFrom(String word) {
        if (word == null) return null;
        String w = word.toLowerCase(Locale.ROOT).replaceAll("(men|man|s)$", "");
        Map<String, Role> alias = new LinkedHashMap<>();
        alias.put("fisher", Role.FISHER);
        alias.put("fish", Role.FISHER);
        alias.put("guard", Role.WARRIOR);
        alias.put("soldier", Role.WARRIOR);
        alias.put("fighter", Role.WARRIOR);
        alias.put("courier", Role.DISTRIBUTOR);
        alias.put("supplier", Role.DISTRIBUTOR);
        alias.put("crafter", Role.parse("maker"));
        alias.put("smith", Role.parse("maker"));
        alias.put("puller", Role.parse("pearler"));
        alias.put("pearl", Role.parse("pearler"));
        Role r = alias.get(w);
        if (r != null) return r;
        for (Role role : Role.values()) {
            if (role.id().equals(w) || role.id().equals(word) || role.displayName.equalsIgnoreCase(word)
                    || role.id().startsWith(w) && w.length() >= 4) return role;
        }
        return null;
    }

    // ------------------------------------------------------------------ running commands

    /** Run a command; its feedback shows up in the master chat. */
    static void run(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) { say("§cNot connected to a server"); return; }
        String cmd = (command.startsWith("/") ? command.substring(1) : command).strip().replaceAll("\\s+", " ");
        say("§7> /" + cmd);
        boolean client = cmd.equals("agent") || cmd.startsWith("agent ") || cmd.equals("load") || cmd.startsWith("load ");
        if (!client) { mc.getConnection().sendCommand(cmd); return; }
        try {
            ClientCommands.getActiveDispatcher().execute(cmd, capturing((FabricClientCommandSource) mc.getConnection().getSuggestionsProvider()));
        } catch (Exception e) {
            say("§c" + e.getMessage());
        }
    }

    /** The normal command source, but feedback also lands in the master chat. */
    private static FabricClientCommandSource capturing(FabricClientCommandSource real) {
        return (FabricClientCommandSource) Proxy.newProxyInstance(MasterAgent.class.getClassLoader(),
                new Class<?>[]{FabricClientCommandSource.class}, (proxy, method, args) -> {
                    if (args != null && args.length == 1 && args[0] instanceof Component c) {
                        if (method.getName().equals("sendFeedback")) say(c.getString());
                        else if (method.getName().equals("sendError")) say("§c" + c.getString());
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ------------------------------------------------------------------ small LLM (optional)

    private static Map<String, String> config() {
        Map<String, String> c = new LinkedHashMap<>(Map.of("llm", "auto", "url", "http://localhost:11434", "model", "hermes3:8b",
                "provider", "local", "api_key", "", "api_url", "", "api_model", ""));
        Path f = AgentFiles.dir().resolve("master.txt");
        try {
            if (!Files.exists(f)) {
                Files.write(f, List.of("# Master agent settings",
                        "# llm: auto (use Ollama if it's running), off",
                        "llm=auto", "url=http://localhost:11434", "# any Ollama model; hermes3:8b follows the command format best, qwen2.5:3b is lighter",
                        "model=hermes3:8b"), StandardCharsets.UTF_8);
            }
            List<String> have = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (have.stream().noneMatch(l -> l.startsWith("provider=") || l.startsWith("# provider"))) {
                // An older settings file: the API options added below what is there (nothing of the user's is changed).
                List<String> more = new ArrayList<>(have);
                more.addAll(List.of("",
                        "# Where the master AI runs. local (default): the Ollama model above, on this computer.",
                        "# anthropic: Claude through the Anthropic API. openai: any OpenAI-compatible API (set api_url and api_model).",
                        "# An API needs no graphics card and copes with a very large civilization; it costs money per request.",
                        "provider=local",
                        "# The API token. Leave empty to read it from the environment variable AGENTNET_API_KEY instead",
                        "# (for anthropic, ANTHROPIC_API_KEY works too). Keep this file to yourself once a token is in it.",
                        "api_key=",
                        "# Empty = the provider's own address (anthropic: https://api.anthropic.com, openai: https://api.openai.com/v1)",
                        "api_url=",
                        "# Empty = claude-opus-5-5 for anthropic. For openai-compatible APIs this must be set.",
                        "api_model="));
                Files.write(f, more, StandardCharsets.UTF_8);
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq < 1) continue;
                c.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException ignored) {}
        return c;
    }

    /** local (Ollama on this computer), anthropic, or openai (any OpenAI-compatible API). */
    private static String provider(Map<String, String> cfg) {
        String p = cfg.getOrDefault("provider", "local").toLowerCase(java.util.Locale.ROOT);
        return p.startsWith("anthropic") || p.equals("claude") ? "anthropic" : p.startsWith("openai") || p.equals("api") ? "openai" : "local";
    }

    /** The API token: from the settings file, else from the environment. Never logged. */
    private static String apiKey(Map<String, String> cfg) {
        String k = cfg.getOrDefault("api_key", "");
        if (k.isBlank()) k = System.getenv("AGENTNET_API_KEY");
        if ((k == null || k.isBlank()) && provider(cfg).equals("anthropic")) k = System.getenv("ANTHROPIC_API_KEY");
        return k == null ? "" : k.strip();
    }

    private static String apiModel(Map<String, String> cfg) {
        String m = cfg.getOrDefault("api_model", "");
        return !m.isBlank() ? m : provider(cfg).equals("anthropic") ? "claude-opus-5-5" : "";
    }

    /** What to tell the user when the chosen API can not be used at all (no token, no model); null when it can. */
    private static String apiProblem(Map<String, String> cfg) {
        String p = provider(cfg);
        if (p.equals("local")) return null;
        if (apiKey(cfg).isEmpty()) return "provider=" + p + " but there is no API token: put it in agentnet/master.txt (api_key=) or in the environment variable AGENTNET_API_KEY";
        if (apiModel(cfg).isEmpty()) return "provider=" + p + " needs api_model= in agentnet/master.txt";
        return null;
    }

    /** Where the model is, for messages to the user. */
    private static String llmWhere(Map<String, String> cfg) {
        String p = provider(cfg);
        if (p.equals("local")) return cfg.get("url");
        String u = cfg.getOrDefault("api_url", "");
        return !u.isBlank() ? u : p.equals("anthropic") ? "https://api.anthropic.com" : "https://api.openai.com/v1";
    }

    /**
     * One request to the model, wherever it runs. msgs are {role, content} with roles system/user/assistant;
     * json = the answer should be one JSON object (the local model gets the schema, an API is asked in words).
     */
    private static HttpRequest llmRequest(Map<String, String> cfg, JsonArray msgs, boolean json) {
        String p = provider(cfg);
        JsonObject body = new JsonObject();
        if (p.equals("local")) {
            body.addProperty("model", cfg.get("model"));
            body.addProperty("stream", false);
            body.addProperty("keep_alive", "30m"); // stay in VRAM between messages
            if (json) {
                body.add("format", answerSchema());
                JsonObject opts = new JsonObject();
                opts.addProperty("temperature", 0.2);
                body.add("options", opts);
            }
            body.add("messages", msgs);
            return HttpRequest.newBuilder(URI.create(cfg.get("url") + "/api/chat"))
                    .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        }
        String model = apiModel(cfg), base = llmWhere(cfg).replaceAll("/+$", "");
        String jsonAsk = "\n\nAnswer with one JSON object and nothing else: {\"commands\": [\"/agent ...\"], \"reply\": \"a short sentence\"}";
        if (p.equals("anthropic")) {
            // The Messages API: the system text apart, then the turns. No sampling parameters (the current models refuse them).
            StringBuilder system = new StringBuilder();
            JsonArray turns = new JsonArray();
            for (var e : msgs) {
                JsonObject m = e.getAsJsonObject();
                if (m.get("role").getAsString().equals("system")) system.append(system.length() > 0 ? "\n\n" : "").append(m.get("content").getAsString());
                else turns.add(msg(m.get("role").getAsString(), m.get("content").getAsString()));
            }
            if (json) system.append(jsonAsk);
            body.addProperty("model", model);
            body.addProperty("max_tokens", 16000);
            if (system.length() > 0) body.addProperty("system", system.toString());
            body.add("messages", turns);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                    .timeout(Duration.ofSeconds(240)).header("Content-Type", "application/json")
                    .header("x-api-key", apiKey(cfg)).header("anthropic-version", "2026-10-21");
            // A request one of these models declines is passed on to another model by the server, instead of just stopping.
            if (model.startsWith("claude-opus-5") || model.startsWith("claude-sonnet-5-5") || model.startsWith("claude-fable-5-1") || model.startsWith("claude-fable-5-2")) {
                body.addProperty("fallbacks", "default");
                b.header("anthropic-beta", "server-side-fallback-2026-07-01");
            }
            return b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        }
        // Any OpenAI-compatible chat completions API.
        JsonArray turns = new JsonArray();
        boolean asked = false;
        for (var e : msgs) {
            JsonObject m = e.getAsJsonObject();
            String content = m.get("content").getAsString();
            if (json && !asked && m.get("role").getAsString().equals("system")) { content += jsonAsk; asked = true; }
            turns.add(msg(m.get("role").getAsString(), content));
        }
        body.addProperty("model", model);
        body.add("messages", turns);
        return HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(240)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey(cfg))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }

    /** The text of the model's answer out of the response body. Throws when there is none (a refusal, an odd body). */
    private static String llmText(Map<String, String> cfg, String responseBody) {
        JsonObject o = JsonParser.parseString(responseBody).getAsJsonObject();
        String p = provider(cfg);
        if (p.equals("local")) return o.getAsJsonObject("message").get("content").getAsString();
        if (p.equals("anthropic")) {
            if (o.has("stop_reason") && !o.get("stop_reason").isJsonNull() && o.get("stop_reason").getAsString().equals("refusal"))
                throw new IllegalStateException("the model declined to answer");
            StringBuilder text = new StringBuilder();
            for (var e : o.getAsJsonArray("content")) {
                JsonObject block = e.getAsJsonObject();
                if (block.get("type").getAsString().equals("text")) text.append(block.get("text").getAsString());
            }
            if (text.length() == 0) throw new IllegalStateException("the answer had no text in it");
            return text.toString();
        }
        return o.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
    }

    /** Why an API call failed, in a few words (the status and the API's own message, never the token). */
    private static String llmError(HttpResponse<String> resp) {
        if (resp == null) return "no answer";
        String detail = "";
        try {
            JsonObject err = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonObject("error");
            if (err != null && err.has("message")) detail = ": " + err.get("message").getAsString();
        } catch (RuntimeException ignored) {}
        if (detail.length() > 160) detail = detail.substring(0, 160) + "...";
        return "HTTP " + resp.statusCode() + (resp.statusCode() == 401 ? " (the API token was not accepted)" : resp.statusCode() == 429 ? " (rate limit or out of credit)" : "") + detail;
    }

    private static final String SYSTEM = """
            You are the master agent of AgentNet, a crew of bot players in Minecraft. The player gives you
            orders in plain words; you carry them out ONLY with these commands:
            /agent spawn <count> <role>                new agents join and take a role
            /agent join <count|name>                   new agents without a role
            /agent leave <all|count|name>              log agents off ("send home", "dismiss", "we're done")
            /agent role <name|all> <role>              change an agent's job
            /agent mine <name|all> <block> <amount>    mine blocks (the agent becomes a miner and gets its own pickaxe)
            /agent explore <name>                      walk around and map the area
            /agent loot <name|all>                     loot generated structures (temples, dungeons, trial chambers...)
            /agent golem <name>                        kill a nearby iron golem from a pillar (iron)
            /agent hunt <name|all>                     hunt animals, pick berries, take village food
            /agent make <name> <item> <count>          craft or smelt items (makers and distributors)
            /agent goto <name|all> <x> <y> <z>
            /agent come <name|all>                     walk over to the player
            /agent follow <name|all> <player>
            /agent stop <name|all>                     drop the current task
            /agent importance <name|all> <1-10>
            /agent war <prepare|on|off>
            /agent list
            /load                                      bring back the saved crew
            Roles (exactly these words; fighting/guarding = warrior, supplies/logistics = distributor,
            crafting/smelting = maker):
            %s
            Block ids for mine: logs (any wood), stone, coal_ore, iron_ore, copper_ore, gold_ore, redstone_ore,
            lapis_ore, diamond_ore, emerald_ore, ancient_debris, sand, gravel, clay, obsidian.
            Item ids are Minecraft ids without "minecraft:" (iron_pickaxe, torch, bread...).
            Rules:
            - Use only agent names from the crew below (or "all"). Never placeholders like <name>.
- Use the agents that are already there. Only spawn or join NEW agents when the player asks for new
  ones (hire, spawn, recruit, "a new ...") or when no agent in the crew is free for the job.
- "/agent mine" already makes the agent a miner: never add a role command before it.
- "The miners", "the farmers"... = only agents whose role is exactly that. Role none = no job yet.
- Agents with role none or task idle are the free ones; give them work first.
            - One command per agent and action. To split work, give each agent its own command.
            - "Everyone"/"all of them" = all. Amounts: split totals between the agents.
            - If the order needs nothing done, or is a question, give no commands and just answer it
              from the crew state.
            Answer as JSON: {"commands": ["/agent ...", ...], "reply": "one short sentence for the player"}
            Example: "Bob and Cid get me 20 iron" ->
            {"commands": ["/agent mine Bob iron_ore 10", "/agent mine Cid iron_ore 10"], "reply": "Bob and Cid are mining 10 iron each."}
            Example: "get me a guard" -> {"commands": ["/agent spawn 1 warrior"], "reply": "A warrior is on the way."}

            The crew right now:
            %s
            Settlement: %s""";

    /** The last few exchanges, so "now send them home" knows who "them" is. */
    private static final java.util.Deque<JsonObject> HISTORY = new java.util.ArrayDeque<>();
    private static final int HISTORY_MAX = 8;

    private static JsonObject answerSchema() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject cmds = new JsonObject();
        cmds.addProperty("type", "array");
        JsonObject str = new JsonObject();
        str.addProperty("type", "string");
        cmds.add("items", str);
        props.add("commands", cmds);
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "string");
        props.add("reply", reply);
        s.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("commands");
        req.add("reply");
        s.add("required", req);
        return s;
    }

    /** One line per agent: what the model needs to answer "who's idle", "who has iron", "where is Bob". */
    static String crewState() {
        if (AgentNetClient.AGENTS.isEmpty()) return "(no agents online)";
        StringBuilder sb = new StringBuilder();
        for (Agent a : AgentNetClient.AGENTS.values()) {
            sb.append("- ").append(a.name).append(": role ").append(a.role() == null ? "none" : a.role().id())
              .append(", task \"").append(a.task).append("\"");
            String st = a.moduleStatus();
            if (st != null && !st.isBlank()) sb.append(", doing: ").append(st.replaceAll("§.", ""));
            sb.append(String.format(Locale.ROOT, ", at %d %d %d", Math.round(a.x()), Math.round(a.y()), Math.round(a.z())));
            if (!Float.isNaN(a.health())) sb.append(String.format(Locale.ROOT, ", %.0f/%.0f HP, food %d", a.health(), a.maxHealth(), a.food()));
            Map<String, Integer> items = new LinkedHashMap<>();
            for (var s : a.inventory()) if (!s.isEmpty()) items.merge(dev.agentnet.role.Gear.id(s).replace("minecraft:", ""), s.getCount(), Integer::sum);
            if (!items.isEmpty()) {
                sb.append(", carries ");
                sb.append(items.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(10)
                        .map(e -> e.getValue() + " " + e.getKey()).reduce((x, y) -> x + ", " + y).orElse(""));
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    private static final java.util.ArrayDeque<String> AFK = new java.util.ArrayDeque<>();

    /** The AFK checker reports an idle agent: shown in the master chat and given to the model to act on. Main thread. */
    static void afk(String text) {
        say("§e[AFK] " + text);
        AFK.removeIf(t -> t.regionMatches(0, text, 0, text.indexOf(' ') + 1)); // one waiting report per agent
        AFK.add(text);
    }

    /** One waiting AFK report goes to the model when it isn't busy. Main thread, every second. */
    static void drainAfk() {
        if (AFK.isEmpty() || thinking) return;
        if (config().get("llm").equalsIgnoreCase("off")) { AFK.clear(); return; }
        askLlm(AFK.poll());
    }

    private static void askLlm(String text) {
        Map<String, String> cfg = config();
        if (cfg.get("llm").equalsIgnoreCase("off")) { say("§eI didn't understand that. Try: get a new farmer, get 3 fishers, make Bob a miner, Bob mine 10 iron, list"); return; }
        if (thinking) { say("§eStill thinking about the last one..."); return; }
        thinking = true;
        String roles = String.join("\n", java.util.Arrays.stream(Role.values()).map(r -> "- " + r.id() + ": " + r.description).toList());
        String summary;
        try { summary = Civ.summary().replaceAll("§.", ""); } catch (RuntimeException e) { summary = "unknown"; }
        JsonArray msgs = new JsonArray();
        msgs.add(msg("system", SYSTEM.formatted(roles, crewState(), summary)));
        for (JsonObject h : HISTORY) msgs.add(h);
        JsonObject user = msg("user", text);
        msgs.add(user);
        chat(cfg, msgs, user, true);
    }

    /** One round with the model; invalid commands go back to it once with the errors. */
    private static void chat(Map<String, String> cfg, JsonArray msgs, JsonObject user, boolean mayRetry) {
        String problem = apiProblem(cfg);
        if (problem != null) { thinking = false; say("§eI didn't understand that, and the master AI can't be asked: " + problem + "."); return; }
        boolean local = provider(cfg).equals("local");
        HttpRequest req = llmRequest(cfg, msgs, true);
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> Minecraft.getInstance().execute(() -> {
            if (err != null || resp.statusCode() != 200) {
                thinking = false;
                if (local) {
                    say("§eI didn't understand that, and no LLM is reachable at " + cfg.get("url")
                            + (resp != null ? " (HTTP " + resp.statusCode() + ")" : "") + ".");
                    say("§7Install Ollama and run: ollama pull " + cfg.get("model") + "  (settings in agentnet/master.txt)");
                } else {
                    say("§eI didn't understand that, and the API at " + llmWhere(cfg) + " did not answer: " + llmError(resp) + ".");
                    say("§7Settings in agentnet/master.txt (provider, api_key, api_model); provider=local goes back to the model on this computer.");
                }
                return;
            }
            String answer;
            List<String> commands = new ArrayList<>();
            String reply = "";
            try {
                answer = llmText(cfg, resp.body());
                try {
                    // An API model may wrap the object in a code fence or a sentence: the object itself is what counts.
                    String js = answer.strip();
                    int from = js.indexOf('{'), to = js.lastIndexOf('}');
                    if (!js.startsWith("{") && from >= 0 && to > from) js = js.substring(from, to + 1);
                    JsonObject o = JsonParser.parseString(js).getAsJsonObject();
                    if (o.has("commands")) for (var e : o.getAsJsonArray("commands")) commands.add(e.getAsString());
                    if (o.has("reply")) reply = o.get("reply").getAsString();
                } catch (RuntimeException notJson) { // a model that ignored the format: plain lines
                    List<String> talk = new ArrayList<>();
                    for (String line : answer.split("\\R")) {
                        String l = line.strip().replaceAll("^[`*\\-\\s]+|[`*]+$", "");
                        if (l.startsWith("/")) commands.add(l); else if (!l.isEmpty()) talk.add(l);
                    }
                    reply = String.join(" ", talk);
                }
            } catch (RuntimeException e) {
                thinking = false;
                say("§cThe LLM's answer made no sense: " + e.getMessage());
                return;
            }
            AgentNet.LOG.info("[Master] model answer: {}", answer.replace('\n', ' '));
            List<String> good = new ArrayList<>();
            List<String> bad = new ArrayList<>();
            for (String c : commands) {
                String fixed = fixCommand(c);
                if (fixed == null) continue;
                String why = check(fixed);
                if (why == null) good.add(fixed); else bad.add(fixed + "  -> " + why);
            }
            good = sanitize(user.get("content").getAsString(), good);
            if (!bad.isEmpty() && mayRetry) {
                // Let it fix its own mistakes once, then run whatever is valid.
                AgentNet.LOG.info("[Master] invalid commands, asking again: {}", bad);
                JsonArray again = msgs.deepCopy();
                again.add(msg("assistant", answer));
                again.add(msg("user", "These commands are invalid:\n" + String.join("\n", bad)
                        + "\nAnswer again with the full corrected JSON (all commands, fixed). Use only the listed command forms and agent names."));
                chat(cfg, again, user, false);
                return;
            }
            thinking = false;
            for (String b : bad) say("§7(skipped invalid: " + b + ")");
            for (String c : good) run(c);
            if (!reply.isBlank()) say("§bMaster: §f" + reply);
            else if (good.isEmpty()) say("§bMaster: §fI'm not sure what to do with that.");
            HISTORY.add(user);
            HISTORY.add(msg("assistant", (good.isEmpty() ? "" : String.join("\n", good) + "\n") + reply));
            while (HISTORY.size() > HISTORY_MAX) HISTORY.poll();
        }));
    }

    /**
     * Repair the usual small-model slips: missing slash, "everyone" for all, wrong-case names,
     * role synonyms, "iron" for iron_ore, "minecraft:" prefixes, amount before block. Null = drop it.
     */
    static String fixCommand(String c) {
        String l = c.strip().replaceAll("^[`*\\-\\s]+|[`*.]+$", "");
        if (l.isEmpty()) return null;
        if (!l.startsWith("/")) l = "/" + l;
        l = l.replace("minecraft:", "");
        String[] p = l.split("\\s+");
        if (!p[0].equals("/agent")) return p[0].equals("/load") ? l : null; // only AgentNet commands
        if (p.length >= 3) {
            String who = p[2];
            if (who.equalsIgnoreCase("everyone") || who.equalsIgnoreCase("everybody") || who.equalsIgnoreCase("all")) p[2] = "all";
            else {
                Agent a = AgentNetClient.AGENTS.get(who.toLowerCase(Locale.ROOT));
                if (a != null) p[2] = a.name;
            }
        }
        if (p.length >= 4 && p[1].equals("mine")) {
            if (p[3].matches("\\d+") && p.length >= 5) { String t = p[3]; p[3] = p[4]; p[4] = t; } // "mine Bob 10 iron"
            String b = mineBlock(p[3].toLowerCase(Locale.ROOT));
            if (b != null) p[3] = b;
        }
        if (p.length == 4 && (p[1].equals("role") || p[1].equals("spawn")) && Role.parse(p[3]) == null) {
            Role r = roleFrom(p[3]);
            if (r != null) p[3] = r.id();
        }
        return String.join(" ", p);
    }

    private static final Pattern HIRE = Pattern.compile("\\b(new|hire|spawn|recruit|join|another|extra|more (agents?|people|workers|bots|\\w+ers?|\\w+men))\\b");
    private static final Pattern JOBLESS = Pattern.compile("\\b(without (a |any )?(job|role)|jobless|unemployed|no (job|role))\\b");

    /** Asked for new agents: "hire 2", "a new farmer", "get me a guard", "3 fishers". */
    static boolean wantsNew(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (HIRE.matcher(t).find()) return true;
        String[] w = t.replaceAll("[^a-z0-9 ]", " ").split("\\s+");
        // "Rocky should be a miner" is about Rocky, not a new miner.
        for (String x : w) if (AgentNetClient.AGENTS.containsKey(x)) return false;
        for (int i = 0; i + 1 < w.length; i++) {
            if (!(NUMBERS.containsKey(w[i]) || w[i].matches("\\d+"))) continue;
            if (roleFrom(w[i + 1]) != null) return true; // "a guard", "3 fishers"
        }
        return false;
    }

    /**
     * Keep only what the player's sentence justifies. Small models add extras: spawning agents
     * nobody asked for, joining agents that are online already, changing the job of agents the
     * player didn't mention, a role command before "mine" (which sets the role itself), gotos
     * to made-up coordinates.
     */
    static List<String> sanitize(String text, List<String> cmds) {
        String t = text.toLowerCase(Locale.ROOT);
        boolean everyone = t.matches(".*\\b(everyone|everybody|all|all of them|the crew|whole crew)\\b.*");
        boolean jobless = JOBLESS.matcher(t).find();
        boolean hire = wantsNew(text);
        boolean coords = t.replaceAll("[^0-9-]+", " ").strip().split("\\s+").length >= 3 && t.matches(".*\\d.*");
        java.util.Set<String> mined = new java.util.HashSet<>(), spawned = new java.util.HashSet<>();
        for (String c : cmds) {
            String[] p = c.split("\\s+");
            if (p.length >= 3 && p[1].equals("mine")) mined.add(p[2].toLowerCase(Locale.ROOT));
            if (p.length >= 4 && p[1].equals("spawn") && hire) spawned.add(p[3]);
        }
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String c : cmds) {
            String[] p = c.split("\\s+");
            String why = null;
            String verb = p.length > 1 ? p[1] : "";
            Agent a = p.length > 2 ? AgentNetClient.AGENTS.get(p[2].toLowerCase(Locale.ROOT)) : null;
            boolean named = a != null && t.matches("(?s).*\\b" + Pattern.quote(a.name.toLowerCase(Locale.ROOT)) + "\\b.*");
            // The agent's current role mentioned ("the miners", "my farmer")?
            boolean roleNamed = a != null && a.role() != null && t.matches("(?s).*\\b" + Pattern.quote(a.role().id()) + "(s|men)?\\b.*");
            if (!seen.add(c)) why = "duplicate";
            // Crew jobs the autopilot keeps filled (food, storage, rescue...): an idle report is no reason to send one off
            // mining; only the player saying so (a role change asked for in words) moves them. Three farmers became miners that way.
            else if (a != null && a.role() != null && java.util.Set.of(Role.FARMER, Role.HUNTER, Role.KEEPER, Role.BUILDER, Role.DISTRIBUTOR, Role.RESCUER, Role.TRIAL_GRINDER, Role.SPEEDRUNNER, Role.PICKUP).contains(a.role())
                    && java.util.Set.of("role", "mine", "hunt", "loot", "explore", "leave", "golem").contains(verb) && !t.matches("(?s).*\b(role|become|switch|turn into|make (him|her|them|it)? ?an?)\b.*"))
                why = a.name + " is a " + a.role().id() + ": that job stays filled (the player didn't ask to change it)";
            else if (verb.equals("role") && a != null && p.length > 3 && a.role() != null && a.role() == Role.parse(p[3])) why = a.name + " is a " + p[3] + " already (that changes nothing)";
            else if ((verb.equals("spawn") || verb.equals("join")) && !hire) why = "the player didn't ask for new agents";
            else if (verb.equals("join") && p.length > 2 && AgentNetClient.AGENTS.containsKey(p[2].toLowerCase(Locale.ROOT))) why = p[2] + " is online already";
            else if (verb.equals("role") && p.length > 2 && (mined.contains(p[2].toLowerCase(Locale.ROOT)) || mined.contains("all"))) why = "mine sets the role itself";
            else if (verb.equals("goto") && !coords) why = "no coordinates were given";
            else if (verb.equals("role") && p.length > 3 && spawned.contains(p[3]) && !named) why = "new " + p[3] + "s are being spawned for that";
            else if (a != null && !named && !roleNamed && (verb.equals("role") || verb.equals("mine") || verb.equals("leave") || verb.equals("explore") || verb.equals("make") || verb.equals("loot") || verb.equals("hunt") || verb.equals("golem"))) {
                if (jobless && a.role() != null) why = a.name + " has a job (" + a.role().id() + ")";
                else if (!jobless && a.role() != null && !everyone && (verb.equals("role") || verb.equals("mine") || verb.equals("leave") || verb.equals("loot") || verb.equals("hunt")))
                    why = a.name + " is a " + a.role().id() + " and wasn't mentioned";
            }
            if (why != null) { AgentNet.LOG.info("[Master] dropped \"{}\": {}", c, why); continue; }
            out.add(c);
        }
        return out;
    }

    /** Would this command parse? Null if yes, else the reason (without running it). */
    static String check(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return "not connected";
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        try {
            var dispatcher = ClientCommands.getActiveDispatcher();
            var parse = dispatcher.parse(cmd, (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider());
            if (parse.getReader().canRead()) {
                if (!parse.getExceptions().isEmpty()) return parse.getExceptions().values().iterator().next().getMessage();
                return "unknown or extra argument at \"" + parse.getReader().getRemaining() + "\"";
            }
            if (parse.getContext().getCommand() == null) return "incomplete command";
            String[] p = cmd.split("\\s+");
            // Name checks the command itself would only report after running.
            if (p.length >= 3 && List.of("role", "mine", "explore", "make", "goto", "come", "follow", "stop", "importance", "trade", "loot", "golem", "hunt").contains(p[1])
                    && !p[2].equals("all") && !AgentNetClient.AGENTS.containsKey(p[2].toLowerCase(Locale.ROOT)))
                return "no agent named " + p[2];
            if (p.length >= 4 && (p[1].equals("role") || p[1].equals("spawn")) && Role.parse(p[3]) == null) return "no role " + p[3];
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------ long-term planning (civ "think" mode)

    private static final String PLANNER = """
            You are the long-term planner of a Minecraft settlement run by bot agents. Every few minutes you
            get its state and may give up to %d commands that help it grow over the next hour: more food,
            more iron and coal, better tools, a bigger map, buildings. Prefer reassigning agents without a
            role ("/agent role <name> <role>") over spawning new ones. Population cap: %d agents.
            Allowed commands (exactly these forms):
            /agent spawn <count> <role>
            /agent role <agent> <role>
            /agent mine <agent> <block> <amount>     (blocks: iron_ore, coal_ore, copper_ore, diamond_ore, stone)
            /agent explore <agent>
            /agent make <agent> <item> <count>
            /agent trade <agent> <item> <count>
            Roles:
            %s
            Use only the exact agent names listed in the state (never placeholders).
            Answer with the commands, each on its own line, then one short sentence explaining your plan.
            If nothing needs doing, answer only: nothing to do.""";

    /** Commands the planner may run: nothing that removes agents, starts fights or wars. */
    private static final java.util.regex.Pattern PLANNER_ALLOWED = java.util.regex.Pattern.compile(
            "^/agent (spawn \\d+ \\w+|role \\w+ \\w+|mine \\w+ [a-z_:]+( \\d+)?|explore \\w+|make \\w+ [a-z_:]+( \\d+)?|trade \\w+ [a-z_:]+( \\d+)?)$");

    /**
     * Ask the model what to do next, given the settlement summary; run at most maxCommands of
     * its answer that pass the allow-list (spawning also respects the cap). Async; no-op
     * if the model is off or busy.
     */
    static void plan(String state, int maxCommands, int cap) {
        Map<String, String> cfg = config();
        if (cfg.get("llm").equalsIgnoreCase("off") || thinking) return;
        if (apiProblem(cfg) != null) { AgentNet.LOG.info("[Civ] planner off: {}", apiProblem(cfg)); lastPlanAt = System.currentTimeMillis(); return; }
        thinking = true;
        lastPlanAt = System.currentTimeMillis();
        String roles = String.join("\n", java.util.Arrays.stream(Role.values()).map(r -> "- " + r.id() + ": " + r.description).toList());
        JsonArray msgs = new JsonArray();
        msgs.add(msg("system", PLANNER.formatted(maxCommands, cap, roles)));
        msgs.add(msg("user", "Settlement state: " + state));
        HttpRequest req = llmRequest(cfg, msgs, false);
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> Minecraft.getInstance().execute(() -> {
            thinking = false;
            if (err != null || resp.statusCode() != 200) { AgentNet.LOG.info("[Civ] planner unreachable at {}: {}", llmWhere(cfg), llmError(resp)); return; }
            try {
                String answer = llmText(cfg, resp.body());
                List<String> talk = new ArrayList<>();
                int ran = 0;
                for (String line : answer.split("\\R")) {
                    String l = fixRole(line.strip().replaceAll("^[`*\\-\\s\\d.)]+(?=/)|[`*]+$", ""));
                    if (l.startsWith("/agent ")) {
                        if (ran >= maxCommands || !PLANNER_ALLOWED.matcher(l).matches()) { AgentNet.LOG.info("[Civ] planner suggestion skipped: {}", l); continue; }
                        if (l.startsWith("/agent spawn") && AgentNetClient.AGENTS.size() >= cap) { AgentNet.LOG.info("[Civ] planner spawn skipped (cap): {}", l); continue; }
                        String why = plannerVeto(l);
                        if (why != null) { AgentNet.LOG.info("[Civ] planner suggestion vetoed ({}): {}", why, l); continue; }
                        AgentNet.LOG.info("[Civ] planner ran: {}", l);
                        run(l);
                        ran++;
                    } else if (!l.isEmpty()) talk.add(l);
                }
                String why = String.join(" ", talk);
                if (ran > 0 || !why.toLowerCase(java.util.Locale.ROOT).startsWith("nothing")) say("§bCiv (thinking): §f" + (why.isEmpty() ? "ran " + ran + " command(s)" : why));
            } catch (RuntimeException e) {
                AgentNet.LOG.info("[Civ] planner answer unusable: {}", e.getMessage());
            }
        }));
    }

    /**
     * The rules stay in charge: the model may only use real agent names, give a role to agents
     * that have none, and send miners (only) mining. Null = fine, else why not.
     */
    private static String plannerVeto(String line) {
        String[] p = line.split("\\s+");
        java.util.List<Agent> crew = new java.util.ArrayList<>(AgentNetClient.AGENTS.values());
        // A fisher needs a rod: no spare one, no new fisher.
        if (p.length >= 3 && (p[1].equals("spawn") || p[1].equals("role")) && line.endsWith(" fisher")
                && Civ.count(crew, "minecraft:fishing_rod") <= crew.stream().filter(x -> x.role() == Role.FISHER).count())
            return "no spare fishing rod for a fisher";
        if (p.length < 3 || p[1].equals("spawn")) return null;
        Agent a = AgentNetClient.AGENTS.get(p[2].toLowerCase(java.util.Locale.ROOT));
        if (a == null) return "no agent named " + p[2];
        if (p[1].equals("role") && a.role() != null) return p[2] + " already has a role";
        if (p[1].equals("mine") && a.role() != Role.MINER) return p[2] + " isn't a miner";
        return null;
    }

    static long lastPlanAt;

    /** Load the model in the background (first answer is otherwise ~20 s slower). */
    static void warmUp() {
        Map<String, String> cfg = config();
        if (cfg.get("llm").equalsIgnoreCase("off") || !provider(cfg).equals("local")) return;
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.get("model"));
        body.addProperty("keep_alive", "30m");
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.get("url") + "/api/generate"))
                .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    /** "/agent role Bob soldier" -> "/agent role Bob warrior": the LLM sometimes uses its own words for roles. */
    static String fixRole(String line) {
        String[] p = line.split("\\s+");
        if (p.length == 4 && (p[1].equals("role") || p[1].equals("spawn")) && Role.parse(p[3]) == null) {
            Role r = roleFrom(p[3]);
            if (r != null) return p[0] + " " + p[1] + " " + p[2] + " " + r.id();
        }
        return line;
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
