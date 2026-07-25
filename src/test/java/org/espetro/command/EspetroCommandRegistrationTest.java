package org.espetro.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class EspetroCommandRegistrationTest {

    @Test
    void registersForcedStopSubcommand() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        EspetroCommand.register(dispatcher);

        var root = dispatcher.getRoot().getChild("espetro");
        assertNotNull(root);
        assertNotNull(root.getChild("stop"));
    }
}
