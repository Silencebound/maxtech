package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.neoforge.datagen.LangSection;
import java.util.ArrayList;
import java.util.List;

/** Every section of Lib's {@code en_us}; the language provider adds them all. */
public final class LibLangSections {
    private LibLangSections() {
    }

    public static List<LangSection> all() {
        List<LangSection> all = new ArrayList<>();
        // Append-only, one section per line: .gitattributes merges this file with merge=union, so parallel features
        // adding a line here keep both lines.
        all.add(FacadeLang::addTo);
        all.add(ScannerLang::addTo);
        all.add(ProfileLang::addTo);
        all.add(BlockInfoLang::addTo);
        all.add(EnergyInfoLang::addTo);
        all.add(AmountUnitLang::addTo);
        all.add(ConduitLang::addTo);
        all.add(GuiLang::addTo);
        all.add(RedstoneLang::addTo);
        all.add(MachineLang::addTo);
        all.add(FaceConfigLang::addTo);
        all.add(ConfigLang::addTo);
        all.add(GuideConfigLang::addTo);
        all.add(RecipeViewerLang::addTo);
        return all;
    }
}
