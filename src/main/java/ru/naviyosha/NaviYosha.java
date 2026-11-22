package ru.naviyosha;

import net.fabricmc.api.ModInitializer;

/**
 * Server entry point. Frame behavior is encoded in the result stack's vanilla
 * entity_data component by the bundled recipes. No client registry is added.
 */
public final class NaviYosha implements ModInitializer {
    @Override public void onInitialize() {
        // Vanilla applies Invisible to a frame placed from the crafted stack.
    }
}
