package dev.agentnet;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.agentnet.role.Role;

/** /agent view: one row per role (two columns), [-] value [+]; changes apply to agents at once. */
final class ViewScreen extends Screen {
    private static final int ROW_H = 22, COL_W = 190;
    private final Screen parent;

    ViewScreen(Screen parent) {
        super(Component.literal("AgentNet - view distance per role"));
        this.parent = parent;
    }

    /** Every role plus "no role". */
    private static Role[] rows() {
        Role[] r = Role.values();
        Role[] out = java.util.Arrays.copyOf(r, r.length + 1);
        out[r.length] = null;
        return out;
    }

    private int perColumn() { return (rows().length + 1) / 2; }
    private int top() { return Math.max(36, height / 2 - perColumn() * ROW_H / 2); }
    private int colX(int col) { return width / 2 - COL_W - 6 + col * (COL_W + 12); }

    @Override
    protected void init() {
        Role[] rows = rows();
        for (int i = 0; i < rows.length; i++) {
            Role r = rows[i];
            int x = colX(i / perColumn()), y = top() + (i % perColumn()) * ROW_H;
            addRenderableWidget(Button.builder(Component.literal("-"), b -> RoleView.set(r, RoleView.get(r) - 1)).bounds(x + COL_W - 72, y, 20, 18).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> RoleView.set(r, RoleView.get(r) + 1)).bounds(x + COL_W - 24, y, 20, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 50, top() + perColumn() * ROW_H + 8, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xD00B0E14);
        int y0 = top();
        g.fill(colX(0) - 8, y0 - 28, colX(1) + COL_W + 8, y0 + perColumn() * ROW_H + 34, 0xE0141922);
        g.fill(colX(0) - 8, y0 - 28, colX(1) + COL_W + 8, y0 - 12, 0xFF1C2330);
        g.fill(colX(0) - 8, y0 - 12, colX(1) + COL_W + 8, y0 - 11, 0xFF4F8CFF);
        g.centeredText(font, "§lView distance per role §r§7· chunks the server sends (less = lighter)", width / 2, y0 - 24, 0xFFE6E9EF);
        Role[] rows = rows();
        for (int i = 0; i < rows.length; i++) {
            Role r = rows[i];
            int x = colX(i / perColumn()), y = y0 + (i % perColumn()) * ROW_H;
            int count = 0;
            for (Agent a : AgentNetClient.AGENTS.values()) if (a.role() == r) count++;
            g.fill(x, y + 2, x + 3, y + 16, r == null ? 0xFF555E70 : 0xFF000000 | r.color);
            g.text(font, (r == null ? "No role" : r.displayName) + " §8(" + count + ")", x + 8, y + 5, 0xFFE6E9EF);
            g.centeredText(font, String.valueOf(RoleView.get(r)), x + COL_W - 38, y + 5, 0xFFFFFFFF);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() { minecraft.gui.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
