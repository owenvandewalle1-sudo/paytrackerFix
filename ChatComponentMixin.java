package com.example.paytracker.mixin;

import com.example.paytracker.PayTracker;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Catches every line added to the chat window, however the server sent it. */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, require = 0)
    private Component paytracker$recolor(Component message) {
        try {
            return PayTracker.handle(message);
        } catch (Throwable t) {
            return message;
        }
    }
}
