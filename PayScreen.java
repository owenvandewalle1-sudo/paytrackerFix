package com.example.paytracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The Pay Tracker menu (open it with the keybind from Options > Controls). */
public class PayScreen extends Screen {

    private EditBox manual;
    private Button autoBtn;

    public PayScreen() {
        super(Component.literal("Pay Tracker"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        addRenderableWidget(Button.builder(Component.literal("Load starting balance (/bal)"),
                b -> PayTracker.requestBalance(true)).bounds(cx - 100, 94, 200, 19).build());

        addRenderableWidget(Button.builder(Component.literal("Refresh current balance now"),
                b -> PayTracker.requestBalance(false)).bounds(cx - 100, 115, 200, 19).build());

        autoBtn = Button.builder(Component.literal(PayTracker.autoLabel()), b -> {
            PayTracker.cycleAuto();
            autoBtn.setMessage(Component.literal(PayTracker.autoLabel()));
        }).bounds(cx - 100, 136, 200, 19).build();
        addRenderableWidget(autoBtn);

        manual = new EditBox(this.font, cx - 100, 157, 124, 19, Component.literal("Start balance"));
        manual.setHint(Component.literal("type e.g. 1.5B"));
        addRenderableWidget(manual);
        addRenderableWidget(Button.builder(Component.literal("Set start"), b -> {
            Double v = PayTracker.parseLoose(manual.getValue());
            if (v != null) PayTracker.setStart(v, false);
        }).bounds(cx + 28, 157, 72, 19).build());

        addRenderableWidget(Button.builder(Component.literal("Send test chat colours"),
                b -> PayTracker.sendTest()).bounds(cx - 100, 178, 200, 19).build());

        addRenderableWidget(Button.builder(Component.literal("Forget saved start"),
                b -> PayTracker.forgetStart()).bounds(cx - 100, 199, 98, 19).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.onClose()).bounds(cx + 2, 199, 98, 19).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Minecraft mc = Minecraft.getInstance();
        int cx = this.width / 2;

        line(g, "Pay Tracker", cx, 10, 0xFFFFD27F);

        String ip = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        boolean donut = PayTracker.onDonut(mc);
        line(g, "Account: " + PayTracker.user(), cx, 26, 0xFFFFFFFF);
        line(g, "Server: " + ip + (donut ? "  (DonutSMP detected)" : "  (not DonutSMP - mod is off)"),
                cx, 37, donut ? 0xFF55FF55 : 0xFFFF5555);
        line(g, "Starting balance: " + (PayTracker.start == null ? "not set"
                : "$" + PayTracker.format(PayTracker.start)), cx, 48, 0xFFFFFFFF);
        line(g, "Current balance: " + (PayTracker.balance == null ? "unknown (updates after a payment)"
                : "$" + PayTracker.format(PayTracker.balance)), cx, 59, 0xFFFFFFFF);

        double p = PayTracker.profit();
        line(g, "Profit: " + (p >= 0 ? "+" : "-") + "$" + PayTracker.format(Math.abs(p)), cx, 72,
                p > 0 ? 0xFF55FF55 : p < 0 ? 0xFFFF5555 : 0xFFFFFFFF);

        if (PayTracker.pending != PayTracker.Pending.NONE) {
            line(g, "Waiting for the /bal reply...", cx, 83, 0xFFFFD27F);
        }

        int dy = Math.max(224, this.height - 48);
        line(g, "Last /bal reply: " + cut(PayTracker.lastBalReply), cx, dy, 0xFF888888);
        line(g, "Last 'paid' line: " + cut(PayTracker.lastPaid)
                + (PayTracker.lastPaid.isEmpty() ? "" : (PayTracker.lastPaidMatched ? "  [matched]" : "  [NOT matched]")),
                cx, dy + 11, 0xFF888888);
        line(g, "Last chat line seen: " + cut(PayTracker.lastAny), cx, dy + 22, 0xFF888888);
    }

    private void line(GuiGraphics g, String s, int cx, int y, int color) {
        g.drawString(this.font, s, cx - this.font.width(s) / 2, y, color, true);
    }

    private static String cut(String s) {
        if (s == null || s.isEmpty()) return "-";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
