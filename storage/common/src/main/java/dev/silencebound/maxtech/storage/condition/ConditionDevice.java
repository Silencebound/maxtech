package dev.silencebound.maxtech.storage.condition;

import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternHost;
import dev.silencebound.maxtech.storage.network.ChannelFree;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.entity.player.Player;

/**
 * A condition switch as its GUI sees it, block or part. It holds no channel ({@link ChannelFree}). Its network lists it
 * with the pattern hosts: in request mode (B09 item 14) it offers its item to the planner, in other modes nothing.
 */
public interface ConditionDevice extends SettingsCopyable, ChannelFree, PatternHost {
    ConditionWorker worker();

    /** Whether {@code player} may keep the GUI open: the switch is still there and in reach. */
    boolean stillValid(Player player);

    @Override
    default PatternProvider patternProvider() {
        return worker().craftSource();
    }

    /** The condition for the blueprint card, as the item holds it. */
    @Override
    default DeviceSettings copyableSettings() {
        return new DeviceSettings(DeviceSettings.kind("condition_switch"), () -> {
            DataComponentMap.Builder components = DataComponentMap.builder();
            ConditionTexts.write(components, worker().settings());
            return components.build();
        }, components -> worker().setSettings(ConditionTexts.read(components)));
    }
}
