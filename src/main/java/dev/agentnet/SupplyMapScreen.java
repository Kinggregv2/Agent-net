package dev.agentnet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.agentnet.role.Links;

/**
 * /agent map (or J): the supply chain at a glance. One dot per agent in its role's color, around a circle
 * grouped by role; arrows for who is waiting on / bringing to / collecting from whom. Only the dots and
 * arrows by default: hover a dot for who it is, what it's doing, how long it has been idle and its links.
 * AFK agents get a red ring and are listed at the top.
 */
final class SupplyMapScreen extends Screen {
    private static final int DOT = 8;
    private static final int WAITING = 0xFFFFA726, BRINGING = 0xFF4FD15A, COLLECTING = 0xFF4F8CFF, AFK = 0xFFE53935;

    SupplyMapScreen() { super(Component.literal("AgentNet - supply chain")); }

    private record Node(Agent agent, int x, int y) {}

    private List<Node> layout() {
        List<Agent> agents = new ArrayList<>(AgentNetClient.AGENTS.values());
        agents.removeIf(a -> !a.isOnline());
        agents.sort(Comparator.comparingInt((Agent a) -> a.role() == null ? 999 : a.role().ordinal()).thenComparing(a -> a.name, String.CASE_INSENSITIVE_ORDER));
        List<Node> out = new ArrayList<>();
        int n = agents.size();
        double r = Math.min(width, height - 60) / 2.0 - 30;
        int cx = width / 2, cy = 40 + (height - 40) / 2;
        for (int i = 0; i < n; i++) {
            double ang = -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, n);
            out.add(new Node(agents.get(i), cx + (int) Math.round(Math.cos(ang) * r), cy + (int) Math.round(Math.sin(ang) * r)));
        }
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xE00B0E14);
        List<Node> nodes = layout();
        Map<String, Node> byName = new HashMap<>();
        for (Node nd : nodes) byName.put(nd.agent().name.toLowerCase(), nd);
        List<Links.Link> links = Links.current();

        // Header: legend and the AFK list.
        g.text(font, "§lSupply chain §r§7· hover a dot for details · ", 8, 6, 0xFFE6E9EF);
        int lx = 8 + font.width("Supply chain · hover a dot for details · ") + 6;
        lx = legend(g, lx, "waiting on", WAITING);
        lx = legend(g, lx, "bringing to", BRINGING);
        legend(g, lx, "collecting from", COLLECTING);
        List<String> afk = new ArrayList<>();
        for (Node nd : nodes) if (AfkChecker.afk(nd.agent().name)) afk.add(nd.agent().name);
        g.text(font, afk.isEmpty() ? "§7AFK: nobody" : "§cAFK (" + afk.size() + "): §f" + String.join(", ", afk), 8, 20, 0xFFE6E9EF);

        // Arrows first, dots on top.
        for (Links.Link l : links) {
            Node a = byName.get(l.from().toLowerCase()), b = byName.get(l.to().toLowerCase());
            if (a == null || b == null) continue;
            int color = switch (l.kind()) { case WAITING -> WAITING; case BRINGING -> BRINGING; case COLLECTING -> COLLECTING; };
            arrow(g, a.x(), a.y(), b.x(), b.y(), color);
        }
        Node hover = null;
        for (Node nd : nodes) {
            int x = nd.x(), y = nd.y();
            boolean over = Math.abs(mouseX - x) <= DOT && Math.abs(mouseY - y) <= DOT;
            if (over) hover = nd;
            if (AfkChecker.afk(nd.agent().name)) g.fill(x - DOT / 2 - 2, y - DOT / 2 - 2, x + DOT / 2 + 2, y + DOT / 2 + 2, AFK);
            if (over) g.fill(x - DOT / 2 - 1, y - DOT / 2 - 1, x + DOT / 2 + 1, y + DOT / 2 + 1, 0xFFFFFFFF);
            // The same colours as the names: the main speedrunner deep purple with a crown and a white rim, its helpers light
            // purple (all four were one purple here: the user could not tell the main apart).
            int rc = AgentNetClient.roleColor(nd.agent().name);
            int c = rc < 0 ? 0xFF8A93A6 : 0xFF000000 | rc;
            if (AgentNetClient.isMainRunner(nd.agent().name)) {
                g.fill(x - DOT / 2 - 2, y - DOT / 2 - 2, x + DOT / 2 + 2, y + DOT / 2 + 2, 0xFFFFFFFF);
                g.centeredText(font, "♛", x, y - DOT / 2 - 11, 0xFFFFFFFF);
            }
            g.fill(x - DOT / 2, y - DOT / 2, x + DOT / 2, y + DOT / 2, c);
        }
        if (hover != null) tooltip(g, hover, links, mouseX, mouseY);
        if (nodes.isEmpty()) g.centeredText(font, "§7No agents online", width / 2, height / 2, 0xFFE6E9EF);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private int legend(GuiGraphicsExtractor g, int x, String what, int color) {
        g.fill(x, 9, x + 10, 12, color);
        g.text(font, "§7" + what, x + 13, 6, 0xFFE6E9EF);
        return x + 13 + font.width(what) + 12;
    }

    /** A line from a toward b (stopping short of the dot), with a small head near b. */
    private static void arrow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return;
        double ux = dx / len, uy = dy / len;
        int steps = (int) len - DOT;
        for (int i = DOT; i < steps; i += 1) {
            int px = (int) Math.round(x0 + ux * i), py = (int) Math.round(y0 + uy * i);
            g.fill(px, py, px + 1, py + 1, color);
        }
        // Head: a few pixels across the line, just before the target.
        int hx = (int) Math.round(x1 - ux * (DOT + 2)), hy = (int) Math.round(y1 - uy * (DOT + 2));
        for (int k = -3; k <= 3; k++) {
            int px = (int) Math.round(hx - ux * Math.abs(k) - uy * k), py = (int) Math.round(hy - uy * Math.abs(k) + ux * k);
            g.fill(px, py, px + 2, py + 2, color);
        }
    }

    private void tooltip(GuiGraphicsExtractor g, Node nd, List<Links.Link> links, int mx, int my) {
        Agent a = nd.agent();
        List<String> lines = new ArrayList<>();
        lines.add("§l" + a.name + " §r§7" + AgentNetClient.roleName(a));
        String status = a.moduleStatus();
        lines.add("§fdoing: §7" + (status == null || status.isBlank() ? "-" : status));
        if (a.task != null && !a.task.equals("idle")) lines.add("§ftask: §7" + a.task);
        long idle = AfkChecker.idleMs(a.name);
        if (AfkChecker.afk(a.name)) lines.add("§cAFK for " + idle / 60_000 + " min");
        else if (idle > 30_000) lines.add("§eidle for " + idle / 1000 + " s");
        for (Links.Link l : links) {
            if (l.from().equalsIgnoreCase(a.name)) lines.add("§a→ " + l.to() + "§7: " + l.label());
            if (l.to().equalsIgnoreCase(a.name)) lines.add("§6← " + l.from() + "§7: " + l.label());
        }
        // Inventory: every kind it carries with its total, most first, three to a line. Then what it wears.
        Map<String, Integer> kinds = new java.util.LinkedHashMap<>();
        int used = 0;
        for (net.minecraft.world.item.ItemStack s : a.inventory()) {
            if (s.isEmpty()) continue;
            used++;
            kinds.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
        }
        lines.add("§finventory §7(" + used + "/36 slots)" + (kinds.isEmpty() ? ": empty" : ":"));
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(kinds.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        StringBuilder row = new StringBuilder();
        int inRow = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            row.append(inRow == 0 ? "  §7" : "§8, §7").append(e.getValue()).append("x ").append(e.getKey());
            if (++inRow == 3) { lines.add(row.toString()); row.setLength(0); inRow = 0; }
        }
        if (inRow > 0) lines.add(row.toString());
        List<String> worn = new ArrayList<>();
        for (net.minecraft.world.item.ItemStack s : a.armor()) if (!s.isEmpty()) worn.add(s.getHoverName().getString());
        if (!worn.isEmpty()) lines.add("§fwearing: §7" + String.join(", ", worn));
        int w = 0;
        for (String s : lines) w = Math.max(w, font.width(s.replaceAll("§.", "")));
        int h = lines.size() * 11 + 6;
        int x = Math.min(mx + 12, width - w - 12), y = Math.min(my + 12, height - h - 4);
        g.fill(x - 4, y - 4, x + w + 6, y + h - 2, 0xF0141922);
        g.fill(x - 4, y - 4, x + w + 6, y - 3, 0xFF4F8CFF);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x, y + i * 11, 0xFFE6E9EF);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
