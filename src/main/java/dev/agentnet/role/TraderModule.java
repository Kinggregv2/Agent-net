package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.bus.Message;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * Trader: trades with villagers.
 *
 * <p>"/agent trade &lt;agent&gt; &lt;item&gt; [count]" (e.g. {@code trade Tom emerald 20} to sell
 * crops for emeralds, or {@code trade Tom bookshelf 4}): it visits the villagers around it
 * (48 blocks), reads each one's offers once, and trades with whoever offers the item for
 * something it carries, shift-clicking the result (as many trades as the payment allows)
 * until it has made enough. Offers it has seen are remembered per villager, so it goes
 * straight to the right one next time. Other agents can ask it with TRADE_REQUEST messages.
 *
 * <p>What it buys is collected by distributors like any gatherer's harvest (it keeps the
 * payment items), so it ends up in the storage chests.
 */
final class TraderModule extends GathererModule {
    private static final double VILLAGER_RANGE = 48, TRADE_REACH = 2.8;

    private enum State { IDLE, FIND, WALK, OPEN, TRADE, WAIT }

    final Deque<Message> requests = new ArrayDeque<>();
    private String want;            // result item id
    private int amount, got, startCount;
    private State state = State.IDLE;
    private Seen villager;
    private int timer, wait, trades;
    private State afterWait;
    /** villager entity id -> result item ids it offered when we looked. */
    private final Map<Integer, Set<String>> offersSeen = new HashMap<>();
    private final Set<Integer> useless = new HashSet<>();
    private String status = "";
    private String lastWarning;

    @Override
    public void onTask(AgentHandle a, String task) {
        String[] p = task.trim().split("\\s+");
        if (p[0].equals("idle")) { want = null; state = State.IDLE; status = "stopped"; closeIfOpen(a); return; }
        if (!p[0].equals("trade") || p.length < 2) return;
        start(a, p[1], p.length > 2 ? parse(p[2]) : 1);
    }

    @Override
    public void onMessage(AgentHandle a, Message m) {
        super.onMessage(a, m);
        if (m.type() == Message.Type.TRADE_REQUEST) {
            requests.add(m);
            a.log("trade request queued from " + m.from() + ": " + m.get("count") + "x " + m.get("item"));
        }
    }

    private void start(AgentHandle a, String item, int n) {
        want = item.contains(":") ? item : "minecraft:" + item;
        amount = Math.max(1, n);
        startCount = War.have(a, want);
        got = 0;
        useless.clear();
        a.log("trading for " + amount + "x " + want.replace("minecraft:", ""));
        go(State.FIND);
    }

    private static int parse(String s) {
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return 1; }
    }

    @Override
    public String status() { return status; }

    /** Keep everything except what it traded for: the payment stays, the goods go to storage. */
    @Override
    protected Set<Integer> keepSlots(AgentHandle a) {
        Set<Integer> keep = new HashSet<>();
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (want == null || !Gear.id(inv.get(i)).equals(want)) keep.add(i);
        return keep;
    }

    @Override
    protected void work(AgentHandle a) {
        timer++;
        if (want == null && !requests.isEmpty()) {
            Message m = requests.pollFirst();
            start(a, m.get("item"), parse(m.get("count") == null ? "1" : m.get("count")));
        }
        if (want == null) { status = "waiting for /agent trade"; return; }
        got = War.have(a, want) - startCount;
        if (got >= amount) {
            closeIfOpen(a);
            a.log("done: traded for " + got + "x " + want.replace("minecraft:", ""));
            status = "done (" + got + " " + want.replace("minecraft:", "") + ")";
            want = null;
            go(State.IDLE);
            return;
        }
        switch (state) {
            case IDLE -> {}
            case FIND -> find(a);
            case WALK -> walk(a);
            case OPEN -> open(a);
            case TRADE -> trade(a);
            case WAIT -> {
                // The shift-click after selecting a trade runs right before the wait ends.
                if (wait == 1 && afterWaitAction != null) { afterWaitAction.run(); afterWaitAction = null; }
                if (--wait <= 0) go(afterWait);
            }
        }
    }

    /** A villager known to sell it, else one we haven't looked at yet; nearest first. */
    private void find(AgentHandle a) {
        Seen best = null;
        boolean bestKnown = false;
        for (Seen s : a.entitiesNear(VILLAGER_RANGE)) {
            if (s.player() || s.hostile() || useless.contains(s.id())) continue;
            if (!s.name().equals("villager") && !s.name().equals("wandering_trader")) continue;
            Set<String> seen = offersSeen.get(s.id());
            boolean known = seen != null && seen.contains(want);
            if (seen != null && !known) continue; // looked already: doesn't sell it
            if (best == null || known && !bestKnown || known == bestKnown && s.distance() < best.distance()) { best = s; bestKnown = known; }
        }
        if (best == null) {
            status = "no villager around sells " + want.replace("minecraft:", "");
            warn(a, "no villager within " + (int) VILLAGER_RANGE + " blocks trades " + want.replace("minecraft:", "")
                    + " for something it carries (" + got + "/" + amount + " so far); trying again in 10 s");
            useless.clear();
            offersSeen.clear(); // villagers restock and level up
            pause(20 * 10, State.FIND);
            return;
        }
        villager = best;
        status = "walking to a " + best.name() + " (" + Math.round(best.distance()) + " blocks)";
        go(State.WALK);
    }

    private void walk(AgentHandle a) {
        Seen now = a.entitiesNear(VILLAGER_RANGE).stream().filter(s -> s.id() == villager.id()).findFirst().orElse(null);
        if (now == null) { go(State.FIND); return; }
        villager = now;
        if (now.distance() <= TRADE_REACH) {
            if (a.isNavigating()) a.stopNavigation();
            a.lookAt(now.pos().x, now.pos().y + 1.4, now.pos().z);
            a.interact(now.id());
            go(State.OPEN);
            return;
        }
        if (timer > 20 * 60) { useless.add(now.id()); go(State.FIND); return; }
        if (!a.isNavigating() && timer % 20 == 1) {
            a.navigate((int) Math.floor(now.pos().x), (int) Math.floor(now.pos().y), (int) Math.floor(now.pos().z), 2);
        }
    }

    private void open(AgentHandle a) {
        List<MerchantOffer> offers = a.tradeOffers();
        if (!offers.isEmpty()) {
            Set<String> results = new HashSet<>();
            for (MerchantOffer o : offers) results.add(Gear.id(o.getResult()));
            offersSeen.put(villager.id(), results);
            go(State.TRADE);
            return;
        }
        if (timer == 30) a.interact(villager.id()); // try once more (it may have been turning around)
        if (timer > 60) { closeIfOpen(a); useless.add(villager.id()); go(State.FIND); }
    }

    /** Pick the offer, let the server fill the payment, shift-click the result. */
    private void trade(AgentHandle a) {
        if (!a.containerOpen()) { go(State.FIND); return; }
        List<MerchantOffer> offers = a.tradeOffers();
        int pick = -1;
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer o = offers.get(i);
            if (!Gear.id(o.getResult()).equals(want) || o.isOutOfStock()) continue;
            if (!canPay(a, o)) continue;
            pick = i;
            break;
        }
        if (pick < 0) {
            boolean sells = offers.stream().anyMatch(o -> Gear.id(o.getResult()).equals(want));
            warn(a, sells ? "this villager sells " + want.replace("minecraft:", "") + " but it can't pay (or it's sold out)"
                          : "this villager doesn't sell " + want.replace("minecraft:", ""));
            closeIfOpen(a);
            useless.add(villager.id());
            go(State.FIND);
            return;
        }
        a.selectTrade(pick);
        trades++;
        status = String.format("trading for %s (%d/%d)", want.replace("minecraft:", ""), got, amount);
        // Give the server a moment to move the payment in, then take the result (slot 2) with
        // a shift-click: that trades again and again while the payment lasts.
        afterWaitAction = () -> a.quickMoveContainerSlot(2);
        pause(4, State.TRADE);
    }

    private Runnable afterWaitAction;

    private static boolean canPay(AgentHandle a, MerchantOffer o) {
        ItemStack costA = o.getCostA(), costB = o.getCostB();
        if (!costA.isEmpty() && War.have(a, Gear.id(costA)) < costA.getCount()) return false;
        return costB.isEmpty() || War.have(a, Gear.id(costB)) >= costB.getCount();
    }

    private void closeIfOpen(AgentHandle a) { if (a.containerOpen()) a.closeContainer(); }

    private void go(State s) { state = s; timer = 0; }

    private void pause(int t, State then) {
        wait = t;
        afterWait = then;
        state = State.WAIT;
        timer = 0;
    }

    private void warn(AgentHandle a, String m) {
        if (m.equals(lastWarning)) return;
        lastWarning = m;
        a.log(m);
    }
}
