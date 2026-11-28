package dev.silencebound.maxtech.lib.neoforge.guide;

import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
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
 * GuideME's {@code <Config key="controller.inputPerTick" unit="MU" />}: a mod's config value at {@code key} in bold,
 * followed by {@code unit}, with the path and the default in its tooltip. A key without a namespace is looked up in the
 * page's namespace; where the value comes from is described on {@link GuideConfigText}. An unknown key is drawn as
 * GuideME's red error text.
 */
public final class ConfigTagCompiler extends FlowTagCompiler {
    /** The tag's name. */
    public static final String TAG_NAME = "Config";

    @Override
    public Set<String> getTagNames() {
        return Set.of(TAG_NAME);
    }

    @Override
    protected void compile(PageCompiler compiler, LytFlowParent parent, MdxJsxElementFields el) {
        String key = el.getAttributeString("key", "");
        Optional<GuideConfigText.Ref> ref = GuideConfigText.ref(key, compiler.getPageId().getNamespace());
        Optional<GuideConfigValues.Value> value = ref.flatMap(GuideConfigText::value);
        if (ref.isEmpty() || value.isEmpty()) {
            parent.appendError(compiler, "No config value: " + key, el);
            return;
        }
        String unit = el.getAttributeString("unit", "");
        GuideConfigValues.Value found = value.get();
        LytTooltipSpan span = new LytTooltipSpan();
        span.modifyStyle(style -> style.bold(true));
        span.appendComponent(GuideConfigText.text(found.current(), unit));
        List<Component> lines = new ArrayList<>(3);
        if (found.changed()) {
            lines.add(Component.translatable(GuideConfigText.CHANGED_KEY));
        }
        lines.add(Component.literal(ref.get().path()));
        lines.add(
                Component.translatable(GuideConfigText.DEFAULT_KEY, GuideConfigText.text(found.defaultValue(), unit)));
        span.setTooltip(new TextTooltip(lines));
        parent.append(span);
    }
}
