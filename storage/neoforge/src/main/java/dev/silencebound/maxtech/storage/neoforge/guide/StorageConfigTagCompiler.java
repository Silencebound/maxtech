package dev.silencebound.maxtech.storage.neoforge.guide;

import dev.silencebound.maxtech.storage.guide.GuideConfigText;
import guideme.compiler.PageCompiler;
import guideme.compiler.tags.FlowTagCompiler;
import guideme.document.flow.LytFlowParent;
import guideme.document.flow.LytTooltipSpan;
import guideme.document.interaction.TextTooltip;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;

/**
 * GuideME's {@code <Config key="controller.inputPerTick" unit="MU" />}: the value of Storage's server config at
 * {@code key} in bold, followed by {@code unit}, with the path and the default in its tooltip. Where the value comes
 * from is described on {@link GuideConfigText}. An unknown key is drawn as GuideME's red error text.
 */
public final class StorageConfigTagCompiler extends FlowTagCompiler {
    /** The tag's name. */
    public static final String TAG_NAME = "Config";

    @Override
    public Set<String> getTagNames() {
        return Set.of(TAG_NAME);
    }

    @Override
    protected void compile(PageCompiler compiler, LytFlowParent parent, MdxJsxElementFields el) {
        String key = el.getAttributeString("key", "");
        Optional<String> path = GuideConfigText.path(key);
        Optional<GuideConfigText.Value> value = path.flatMap(GuideConfigText::value);
        if (path.isEmpty() || value.isEmpty()) {
            parent.appendError(compiler, "No Storage server config value: " + key, el);
            return;
        }
        String unit = el.getAttributeString("unit", "");
        GuideConfigText.Value found = value.get();
        LytTooltipSpan span = new LytTooltipSpan();
        span.modifyStyle(style -> style.bold(true));
        span.appendComponent(GuideConfigText.text(found.current(), unit));
        List<Component> lines = new ArrayList<>(3);
        if (found.changed()) {
            lines.add(Component.translatable(GuideConfigText.CHANGED_KEY));
        }
        lines.add(Component.literal(path.get()));
        lines.add(
                Component.translatable(GuideConfigText.DEFAULT_KEY, GuideConfigText.text(found.defaultValue(), unit)));
        span.setTooltip(new TextTooltip(lines));
        parent.append(span);
    }
}
