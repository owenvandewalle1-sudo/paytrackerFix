package com.example.paytracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pay Tracker - client side only, and only active on DonutSMP.
 *
 * Real DonutSMP lines look like:
 *   R0tem410_ paid you $ 2M
 *   You paid R0tem410_ $ 1M (+$ 20K tax)
 *   You have $ 605,781,139
 * The "You have" line is used to keep your balance (and so your profit) up to date by itself.
 */
public class PayTracker implements ClientModInitializer {

    // ---- patterns -------------------------------------------------------
    // The money part: an optional symbol (the $ or the server's custom $ glyph), optional spaces,
    // then the number and an optional K/M/B/T.
    private static final String AMOUNT =
            "([^\\d\\sA-Za-z]{0,3}\\s*)([\\d,]+(?:\\.\\d+)?)([kmbt])?(?![a-z])";

    // "R0tem410_ paid you $ 2M"       groups: 1 name, 2 symbol, 3 number, 4 suffix
    private static final Pattern RECEIVED =
            Pattern.compile("(?i)(\\.?\\w{1,16}) (?:has )?(?:paid|sent) you " + AMOUNT);
    // "You paid R0tem410_ $ 1M (+$ 20K tax)"
    private static final Pattern SENT =
            Pattern.compile("(?i)\\byou (?:paid|sent) (\\.?\\w{1,16}) " + AMOUNT);
    // "You have $ 605,781,139"        groups: 1 symbol, 2 number, 3 suffix
    private static final Pattern HAVE =
            Pattern.compile("(?i)\\byou have\\s+([^\\d\\sA-Za-z]{1,3})\\s*([\\d,]+(?:\\.\\d+)?)([kmbt])?(?![a-z])");
    // Looser patterns, only used for a few seconds after you press a /bal button.
    private static final Pattern BAL =
            Pattern.compile("(?i)(?:\\bbal(?:ance)?\\b|\\byou have\\b)[^\\d]{0,14}?([\\d,]+(?:\\.\\d+)?)\\s?([kmbt])?(?![a-z])");
    private static final Pattern MONEY =
            Pattern.compile("(?i)[^\\d\\sA-Za-z]{1,3}\\s*([\\d,]+(?:\\.\\d+)?)\\s?([kmbt])?(?![a-z])");
    private static final Pattern LOOSE =
            Pattern.compile("(?i)\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kmbt])?");

    private static final int ORANGE = 0xFFAA00;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;

    // ---- state ----------------------------------------------------------
    enum Pending { NONE, START, REFRESH }

    static Double start = null;     // starting balance (saved per account)
    static Double balance = null;   // last known balance
    static double net = 0;          // net payments this session (fallback when balance/start unknown)
    static Pending pending = Pending.NONE;
    static long pendingUntil = 0;

    static int autoSeconds = 0;     // auto /bal interval, 0 = off
    private static long nextAuto = 0;
    private static boolean autoWait = false;
    private static long autoWaitUntil = 0;

    static String lastAny = "";
    static String lastPaid = "";
    static boolean lastPaidMatched = false;
    static String lastBalReply = "";

    private static boolean forceActive = false; // used by the test button
    private static boolean suppress = false;    // test messages must not change your numbers

    private static String lastText = "";
    private static long lastTime = 0;
    private static Component lastOut = null;

    private static KeyMapping openKey;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("paytracker.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Double> saved = new HashMap<>();

    // ---- setup ----------------------------------------------------------
    @Override
    public void onInitializeClient() {
        loadFile();
        Double a = saved.get("auto");
        autoSeconds = a == null ? 0 : a.intValue();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("paytracker", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.paytracker.open", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(new PayScreen());
                }
            }

            // optional automatic /bal
            if (autoSeconds > 0 && client.player != null && onDonut(client)) {
                long now = System.currentTimeMillis();
                if (autoWait && now > autoWaitUntil) autoWait = false;
                if (!autoWait && pending == Pending.NONE && now >= nextAuto) {
                    nextAuto = now + autoSeconds * 1000L;
                    autoWait = true;
                    autoWaitUntil = now + 4000;
                    client.player.connection.sendCommand("bal");
                }
            }
        });

        // Hides the reply to the automatic /bal so it doesn't spam your chat.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            try {
                if (overlay || !autoWait || !onDonut(Minecraft.getInstance())) return true;
                if (System.currentTimeMillis() > autoWaitUntil) return true;
                Double v = anyBalance(message.getString(), true);
                if (v != null) {
                    balance = v;
                    lastBalReply = message.getString();
                    autoWait = false;
                    return false;
                }
            } catch (Throwable ignored) {
            }
            return true;
        });

        // Backup route for system messages. The mixin (ChatComponentMixin) covers everything else;
        // both go through handle(), which de-duplicates, so nothing is counted twice.
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) return message;
            return handle(message);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            net = 0;
            balance = null;
            pending = Pending.NONE;
            autoWait = false;
            nextAuto = System.currentTimeMillis() + 15000;
            start = saved.get("start:" + user());
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("paytracker", "net_label"),
                (graphics, delta) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.options.hideGui || !active(mc)) return;

                    double p = profit();
                    String title = "PROFIT";
                    String text = (p >= 0 ? "+" : "-") + "$" + format(Math.abs(p));
                    int color = p > 0 ? GREEN : p < 0 ? RED : 0xFFFFFF;

                    String sub = null;
                    if (start == null) sub = "no start set - open menu";
                    else if (balance == null) sub = "payments only - sync in menu";

                    int w = Math.max(mc.font.width(title), mc.font.width(text));
                    if (sub != null) w = Math.max(w, mc.font.width(sub));
                    w += 16;
                    int h = sub != null ? 42 : 30;
                    int x = graphics.guiWidth() - w - 6; // top right
                    int y = 6;
                    graphics.fill(x, y, x + w, y + h, 0xCC1A1206);
                    graphics.renderOutline(x, y, w, h, 0xFFD4A017);
                    graphics.drawString(mc.font, title,
                            x + (w - mc.font.width(title)) / 2, y + 5, 0xFFFFD27F, true);
                    graphics.drawString(mc.font, text,
                            x + (w - mc.font.width(text)) / 2, y + 17, 0xFF000000 | color, true);
                    if (sub != null) {
                        graphics.drawString(mc.font, sub,
                                x + (w - mc.font.width(sub)) / 2, y + 30, 0xFFAAAAAA, true);
                    }
                });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) ->
                dispatcher.register(ClientCommandManager.literal("paytracker")
                        .then(ClientCommandManager.literal("reset").executes(c -> {
                            resetSession();
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("test").executes(c -> {
                            sendTest();
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    // ---- state helpers --------------------------------------------------
    static boolean onDonut(Minecraft mc) {
        var server = mc.getCurrentServer();
        if (server == null) return false;
        String ip = server.ip == null ? "" : server.ip.toLowerCase(Locale.ROOT);
        String name = server.name == null ? "" : server.name.toLowerCase(Locale.ROOT);
        return ip.contains("donut") || name.contains("donut");
    }

    static boolean active(Minecraft mc) {
        return forceActive || onDonut(mc);
    }

    static String user() {
        return Minecraft.getInstance().getUser().getName();
    }

    static double profit() {
        return (start != null && balance != null) ? balance - start : net;
    }

    /** Runs /bal and uses the reply as the starting balance (true) or as the current balance (false). */
    static void requestBalance(boolean asStart) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        pending = asStart ? Pending.START : Pending.REFRESH;
        pendingUntil = System.currentTimeMillis() + 6000;
        lastBalReply = "(waiting for reply...)";
        mc.player.connection.sendCommand("bal");
    }

    static void setStart(double value, boolean alsoBalance) {
        start = value;
        if (alsoBalance) {
            balance = value;
            net = 0;
        }
        saved.put("start:" + user(), value);
        saveFile();
    }

    static void forgetStart() {
        start = null;
        saved.remove("start:" + user());
        saveFile();
    }

    static void resetSession() {
        net = 0;
        if (balance != null) setStart(balance, true);
    }

    static void cycleAuto() {
        autoSeconds = autoSeconds == 0 ? 30 : autoSeconds == 30 ? 60 : autoSeconds == 60 ? 120 : 0;
        saved.put("auto", (double) autoSeconds);
        saveFile();
        nextAuto = 0;
    }

    static String autoLabel() {
        return "Auto refresh balance: " + (autoSeconds == 0 ? "OFF" : "every " + autoSeconds + "s");
    }

    static Double parseLoose(String input) {
        Matcher m = LOOSE.matcher(input.trim());
        if (!m.matches()) return null;
        try {
            return parse(m.group(1), m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Puts two fake payment lines in chat so you can see if the colouring works. */
    static void sendTest() {
        Minecraft mc = Minecraft.getInstance();
        forceActive = true;
        suppress = true;
        try {
            mc.gui.getChat().addMessage(Component.literal("TestUser paid you $ 1.5B"));
            mc.gui.getChat().addMessage(Component.literal("You paid TestUser $ 250M (+$ 5M tax)"));
        } finally {
            forceActive = false;
            suppress = false;
        }
    }

    // ---- chat handling --------------------------------------------------
    /** Called for every chat line (by the mixin and the event). Returns the line to display. */
    public static Component handle(Component original) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!active(mc)) return original;

            String s = original.getString();
            long now = System.currentTimeMillis();
            if (lastOut != null && s.equals(lastText) && now - lastTime < 250) {
                return lastOut; // same line already handled a moment ago
            }
            Component out = analyse(original, s);
            lastText = s;
            lastTime = now;
            lastOut = out;
            return out;
        } catch (Throwable t) {
            return original;
        }
    }

    private static Component analyse(Component original, String s) {
        lastAny = s;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains(" paid ") || low.contains(" sent ")) {
            lastPaid = s;
            lastPaidMatched = false;
        }

        Matcher m = RECEIVED.matcher(s);
        if (m.find()) {
            lastPaid = s;
            lastPaidMatched = true;
            double amount = parse(m.group(3), m.group(4));
            if (!suppress) {
                net += amount;
                if (balance != null) balance += amount;
            }
            return recolor(original, s, m, ORANGE, GREEN);
        }

        m = SENT.matcher(s);
        if (m.find()) {
            lastPaid = s;
            lastPaidMatched = true;
            double amount = parse(m.group(3), m.group(4));
            if (!suppress) {
                net -= amount;
                if (balance != null) balance -= amount;
            }
            return recolor(original, s, m, -1, RED);
        }

        // "You have $ 605,781,139": the server tells us the exact balance after every payment.
        Double have = haveBalance(s);
        if (have != null) {
            lastBalReply = s;
            if (pending == Pending.START) {
                setStart(have, true);
                pending = Pending.NONE;
            } else {
                balance = have;
                if (pending == Pending.REFRESH) pending = Pending.NONE;
            }
            return original;
        }

        // Reply to a /bal you asked for (looser matching, only for a few seconds).
        if (pending != Pending.NONE) {
            if (System.currentTimeMillis() > pendingUntil) {
                pending = Pending.NONE;
            } else {
                Double v = anyBalance(s, false);
                if (v != null) {
                    lastBalReply = s;
                    if (pending == Pending.START) setStart(v, true);
                    else balance = v;
                    pending = Pending.NONE;
                }
            }
        }
        return original;
    }

    private static Double haveBalance(String s) {
        Matcher h = HAVE.matcher(s);
        if (!h.find()) return null;
        try {
            return parse(h.group(2), h.group(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Balance from a "You have $ X" line or a /bal reply. */
    private static Double anyBalance(String s, boolean strict) {
        Double v = haveBalance(s);
        if (v != null) return v;
        try {
            Matcher b = BAL.matcher(s);
            if (b.find()) return parse(b.group(1), b.group(2));
            if (!strict) {
                Matcher mo = MONEY.matcher(s);
                if (mo.find()) return parse(mo.group(1), mo.group(2));
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /** Recolors the name (group 1) and the money part, keeping the rest of the line's original styling. */
    private static Component recolor(Component original, String s, Matcher m, int nameColor, int moneyColor) {
        final int nameS = m.start(1), nameE = m.end(1);
        final int moneyS = m.start(2);
        final int moneyE = m.end(4) >= 0 ? m.end(4) : m.end(3);

        MutableComponent out = Component.empty();
        int[] pos = {0};
        original.<Object>visit((style, text) -> {
            int segStart = pos[0];
            int segEnd = segStart + text.length();
            pos[0] = segEnd;
            int cur = segStart;
            while (cur < segEnd) {
                int color = -1;
                int next = segEnd;
                if (cur >= nameS && cur < nameE) {
                    color = nameColor;
                    next = Math.min(next, nameE);
                } else if (cur >= moneyS && cur < moneyE) {
                    color = moneyColor;
                    next = Math.min(next, moneyE);
                } else {
                    if (nameS > cur) next = Math.min(next, nameS);
                    if (moneyS > cur) next = Math.min(next, moneyS);
                }
                Style st = color >= 0 ? style.withColor(color) : style;
                out.append(Component.literal(text.substring(cur - segStart, next - segStart)).withStyle(st));
                cur = next;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    // ---- saving ---------------------------------------------------------
    private static void loadFile() {
        try {
            if (Files.exists(FILE)) {
                Type type = new TypeToken<Map<String, Double>>() {}.getType();
                Map<String, Double> m = GSON.fromJson(Files.readString(FILE), type);
                if (m != null) saved = m;
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveFile() {
        try {
            Files.writeString(FILE, GSON.toJson(saved));
        } catch (IOException ignored) {
        }
    }

    // ---- number helpers -------------------------------------------------
    static double parse(String number, String suffix) {
        double v = Double.parseDouble(number.replace(",", ""));
        if (suffix == null) return v;
        return switch (suffix.toLowerCase(Locale.ROOT).charAt(0)) {
            case 'k' -> v * 1_000;
            case 'm' -> v * 1_000_000;
            case 'b' -> v * 1_000_000_000;
            case 't' -> v * 1_000_000_000_000.0;
            default -> v;
        };
    }

    static String format(double v) {
        if (v >= 1e12) return trim(v / 1e12) + "T";
        if (v >= 1e9) return trim(v / 1e9) + "B";
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "K";
        return trim(v);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
