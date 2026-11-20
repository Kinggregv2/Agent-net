package dev.agentnet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The master agent's window (K): a title bar with the crew count and the autopilot state,
 * the chat with the master on the left (commands or plain sentences), the crew on the right
 * (role color, health and inventory bars), and the selected agent's details below it.
 */
final class MasterScreen extends Screen {
    // Palette: dark slate panels, a blue accent, muted text.
    private static final int BG = 0xD00B0E14, PANEL = 0xE0141922, PANEL_HI = 0xFF1C2330, BAR = 0xFF0F131A;
    private static final int ACCENT = 0xFF4F8CFF, ACCENT_DIM = 0x604F8CFF, LINE = 0xFF263042;
    private static final int TEXT = 0xFFE6E9EF, MUTED = 0xFF8A93A6, GOOD = 0xFF3DDC84, WARN = 0xFFFFB020, BAD = 0xFFFF5A5A;
    private static final int TITLE_H = 22, ROW_H = 16, PAD = 6;

    private EditBox input;
    private Button autopilot;
    private String selected;
    private int listScroll;
    private final List<String> history = new ArrayList<>();
    private int historyIndex;

    MasterScreen() { super(Component.literal("AgentNet - Master")); }

    private int split() { return width * 3 / 5; }

    @Override
    protected void init() {
        int bottom = height - 26;
        input = new EditBox(font, PAD + 4, bottom, split() - PAD * 2 - 60, 18, Component.literal("Talk to the master"));
        input.setMaxLength(256);
        input.setHint(Component.literal("§8get 2 farmers · make Bob a miner · run the base yourself · /agent ..."));
        input.setFocused(true);
        addRenderableWidget(input);
        setInitialFocus(input);
        addRenderableWidget(Button.builder(Component.literal("Send"), b -> send()).bounds(split() - PAD - 52, bottom - 1, 48, 20).build());
        // Title-bar buttons, right-aligned.
        autopilot = addRenderableWidget(Button.builder(autopilotLabel(), b -> {
            if (Civ.enabled) Civ.off(); else Civ.on();
            b.setMessage(autopilotLabel());
        }).bounds(width - PAD - 190, 2, 100, 18).build());
        addRenderableWidget(Button.builder(Component.literal("View distance"), b -> minecraft.gui.setScreen(new ViewScreen(this)))
                .bounds(width - PAD - 86, 2, 86, 18).build());
        MasterAgent.warmUp();
        if (MasterAgent.LOG.isEmpty()) {
            MasterAgent.say("§bMaster: §fHi. Type /agent commands, or things like \"get a new farmer\", \"get 3 fishers\", "
                    + "\"make Bob a miner\", \"list\", or \"run the base yourself\" for the autopilot.");
        }
    }

    private static Component autopilotLabel() {
        return Component.literal(Civ.enabled ? "§aAutopilot: on" : "§7Autopilot: off");
    }

    private void send() {
        String text = input.getValue();
        if (text.isBlank()) return;
        history.add(text);
        historyIndex = history.size();
        input.setValue("");
        MasterAgent.input(text);
        if (autopilot != null) autopilot.setMessage(autopilotLabel());
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (key == 257 || key == 335) { send(); return true; }                       // Enter
        if (key == 265 && !history.isEmpty()) { historyIndex = Math.max(0, historyIndex - 1); input.setValue(history.get(historyIndex)); return true; }
        if (key == 264 && !history.isEmpty()) {
            historyIndex = Math.min(history.size(), historyIndex + 1);
            input.setValue(historyIndex < history.size() ? history.get(historyIndex) : "");
            return true;
        }
        return super.keyPressed(e);
    }

    private int listTop() { return TITLE_H + PAD + 18; }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (mx > split() + PAD && my > listTop() && my < listBottom()) {
            int row = (int) ((my - listTop()) / ROW_H) + listScroll;
            List<Agent> agents = sorted();
            if (row >= 0 && row < agents.size()) {
                String name = agents.get(row).name;
                selected = name.equals(selected) ? null : name;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (mx > split()) { listScroll = Math.max(0, listScroll - (int) Math.signum(dy)); return true; }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private int listBottom() { return selected == null ? height - PAD - 4 : height / 2 + 10; }

    private static List<Agent> sorted() {
        List<Agent> l = new ArrayList<>(AgentNetClient.AGENTS.values());
        l.sort(Comparator.comparingInt((Agent a) -> -a.importance).thenComparing(a -> a.name.toLowerCase()));
        return l;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, BG);
        int sx = split();
        List<Agent> agents = sorted();
        long online = agents.stream().filter(Agent::isOnline).count();

        // ---- title bar
        g.fill(0, 0, width, TITLE_H, PANEL_HI);
        g.fill(0, TITLE_H - 1, width, TITLE_H, ACCENT);
        g.text(font, "§lAgentNet", PAD + 2, 7, TEXT);
        g.text(font, "Master" + (MasterAgent.thinking() ? "  §e● thinking" : ""), PAD + 2 + font.width("§lAgentNet") + 8, 7, MUTED);
        String crew = online + "/" + agents.size() + " online" + (Civ.enabled ? "  ·  autopilot cap " + Civ.cap : "");
        g.text(font, crew, width - PAD - 196 - font.width(crew), 7, MUTED);

        // ---- chat panel
        int top = TITLE_H + PAD;
        panel(g, PAD, top, sx - PAD / 2, height - 32, "Chat");
        int y = height - 36 - font.lineHeight;
        List<String> log = MasterAgent.LOG;
        for (int i = log.size() - 1; i >= 0 && y > top + 16; i--) {
            String line = log.get(i);
            int color = line.startsWith("§fYou:") ? 0xFFB8D0FF : line.startsWith("§7>") ? MUTED : line.contains("§c") ? 0xFFFFB0B0 : TEXT;
            var lines = font.split(Component.literal(line), sx - PAD * 4);
            for (int j = lines.size() - 1; j >= 0 && y > top + 16; j--) {
                g.text(font, lines.get(j), PAD + 6, y, color);
                y -= font.lineHeight + 2;
            }
            y -= 2; // a little air between messages
        }
        // input background
        g.fill(PAD + 2, height - 28, sx - PAD - 56, height - 7, BAR);

        // ---- crew panel
        panel(g, sx + PAD / 2, top, width - PAD, listBottom(), "Crew  §8(" + agents.size() + ", click for details)");
        int bottom = listBottom();
        listScroll = Math.min(listScroll, Math.max(0, agents.size() - (bottom - listTop()) / ROW_H));
        int ry = listTop();
        int rowW = width - PAD - 6 - (sx + PAD / 2 + 4);
        for (int i = listScroll; i < agents.size() && ry + ROW_H <= bottom; i++) {
            Agent a = agents.get(i);
            int x0 = sx + PAD / 2 + 4, x1 = width - PAD - 6;
            boolean hover = mouseX >= x0 && mouseX <= x1 && mouseY >= ry && mouseY < ry + ROW_H;
            if (a.name.equals(selected)) g.fill(x0, ry, x1, ry + ROW_H - 1, ACCENT_DIM);
            else if (hover) g.fill(x0, ry, x1, ry + ROW_H - 1, 0x20FFFFFF);
            int roleColor = a.role() == null ? 0xFF555E70 : 0xFF000000 | Math.max(0, AgentNetClient.roleColor(a.name));
            g.fill(x0, ry, x0 + 3, ry + ROW_H - 1, roleColor);
            g.fill(x0 + 7, ry + 5, x0 + 11, ry + 9, a.isOnline() ? GOOD : 0xFF555E70);
            String role = a.role() == null ? "-" : AgentNetClient.roleName(a);
            int barW = Math.max(24, Math.min(60, rowW / 5));
            int bx = x1 - barW - 4;
            int nameW = Math.max(30, (bx - (x0 + 15)) * 3 / 5 - 4);
            g.text(font, font.plainSubstrByWidth(a.name, nameW), x0 + 15, ry + 4, TEXT);
            int roleX = x0 + 15 + nameW + 6;
            g.text(font, font.plainSubstrByWidth(role, Math.max(0, bx - roleX - 6)), roleX, ry + 4, MUTED);
            // health and inventory bars on the right
            float hp = a.health(), max = Math.max(1f, a.maxHealth());
            bar(g, bx, ry + 3, barW, 4, Float.isNaN(hp) ? 0 : hp / max, hp / max < 0.34f ? BAD : hp / max < 0.67f ? WARN : GOOD);
            bar(g, bx, ry + 9, barW, 3, a.usedSlots() / 36f, ACCENT);
            ry += ROW_H;
        }
        if (agents.isEmpty()) g.text(font, "No agents yet. Try \"get a new farmer\".", sx + PAD + 6, listTop() + 4, MUTED);

        // ---- details
        Agent sel = selected == null ? null : AgentNetClient.AGENTS.get(selected.toLowerCase());
        if (sel != null) {
            int dTop = listBottom() + PAD;
            panel(g, sx + PAD / 2, dTop, width - PAD, height - PAD, sel.name + "  §8[importance " + sel.importance + "]");
            int dy = dTop + 18;
            for (String l : details(sel)) {
                for (var part : font.split(Component.literal(l), width - sx - PAD * 4)) {
                    if (dy > height - PAD - 12) break;
                    g.text(font, part, sx + PAD + 6, dy, TEXT);
                    dy += font.lineHeight + 2;
                }
            }
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    /** A panel with a title strip. */
    private void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, String title) {
        g.fill(x0, y0, x1, y1, PANEL);
        g.fill(x0, y0, x1, y0 + 14, PANEL_HI);
        g.fill(x0, y0 + 14, x1, y0 + 15, LINE);
        g.text(font, "§l" + title, x0 + 6, y0 + 3, TEXT);
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float frac, int color) {
        frac = Math.max(0f, Math.min(1f, frac));
        g.fill(x, y, x + w, y + h, BAR);
        g.fill(x, y, x + Math.round(w * frac), y + h, color);
    }

    private static List<String> details(Agent a) {
        List<String> out = new ArrayList<>();
        out.add("§7Role  §f" + (a.role() == null ? "none" : AgentNetClient.roleName(a))
                + "§7   Health §f" + (Float.isNaN(a.health()) ? "?" : Math.round(a.health())) + "/" + Math.round(a.maxHealth())
                + "§7   Food §f" + a.food() + "/20");
        out.add(String.format("§7Where §f%d %d %d §7in %s", Math.round(a.x()), Math.round(a.y()), Math.round(a.z()),
                a.dimension() == null ? "?" : a.dimension().replace("minecraft:", "")));
        if (!a.moduleStatus().isEmpty()) out.add("§7Doing §f" + a.moduleStatus());
        out.add("§7Task  §f" + a.task + "§7   Travel §f" + a.navStatus());
        Map<String, Integer> items = new LinkedHashMap<>();
        for (ItemStack s : a.inventory()) {
            if (!s.isEmpty()) items.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
        }
        out.add("§7Inventory (" + a.usedSlots() + "/36) §f" + (items.isEmpty() ? "empty"
                : String.join(", ", items.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList())));
        List<String> worn = a.armor().stream().filter(s -> !s.isEmpty()).map(s -> s.getHoverName().getString()).toList();
        if (!worn.isEmpty()) out.add("§7Wearing §f" + String.join(", ", worn));
        return out;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
