package dev.silencebound.maxtech.storage.sensor.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class PackReaderTest {
    private static PackReader parse(String json) {
        return PackReader.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static String error(String json) {
        return PackReader.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().orElseThrow().message();
    }

    @Test
    void fullAndShortValuesKeepFileOrder() {
        PackReader reader = parse("""
                {"blocks": ["examplemod:tank", "#examplemod:machines"],
                 "values": {
                   "stored": {"path": "Tank.Amount", "unit": "mB", "scale": 0.5},
                   "working": {"path": "Active", "type": "yes_no"},
                   "mode": {"path": "Mode", "type": "text"},
                   "progress": "Progress"}}""");
        assertEquals(List.of(Identifier.parse("examplemod:tank")), reader.blocks().ids());
        assertEquals("examplemod:machines", reader.blocks().tags().get(0).location().toString());
        assertEquals(List.of("stored", "working", "mode", "progress"), new ArrayList<>(reader.values().keySet()));
        ReaderValue stored = java.util.Objects.requireNonNull(reader.values().get("stored"));
        assertEquals("mB", stored.unit());
        assertEquals(0.5, stored.scale());
        assertEquals(ReaderType.YES_NO, java.util.Objects.requireNonNull(reader.values().get("working")).type());
        ReaderValue progress = reader.values().get("progress");
        assertEquals(ReaderValue.number(ReaderPath.parse("Progress").getOrThrow()), progress);
    }

    @Test
    void encodesWhatItParses() {
        PackReader reader = parse("""
                {"blocks": ["#c:chests", "minecraft:furnace"],
                 "values": {"a": {"path": "x[0]", "unit": "LP", "type": "yes_no", "scale": 2.0}, "b": "y"}}""");
        JsonElement json = PackReader.CODEC.encodeStart(JsonOps.INSTANCE, reader).getOrThrow();
        assertEquals(reader, PackReader.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals("[\"minecraft:furnace\",\"#c:chests\"]", json.getAsJsonObject().get("blocks").toString());
    }

    @Test
    void errorsNameWhatIsWrong() {
        assertTrue(error("""
                {"blocks": ["mod:a"], "values": {"x": "a..b"}}""")
                .contains("value 'x': a name is missing after '.' at character 3 of 'a..b'"));
        assertTrue(error("""
                {"blocks": ["Not A Block!"], "values": {"x": "a"}}""").contains("'Not A Block!' is not a block id"));
        assertTrue(error("""
                {"blocks": [], "values": {"x": "a"}}""").contains("1"), "an empty block list");
        assertTrue(error("""
                {"blocks": ["mod:a"], "values": {}}""").contains("at least one value"));
        assertTrue(error("""
                {"blocks": ["mod:a"], "values": {"Bad Name": "a"}}""").contains("value name 'Bad Name'"));
        assertTrue(error("""
                {"blocks": ["mod:a"], "values": {"x": {"path": "a", "type": "colour"}}}""").contains("colour"));
        assertTrue(error("""
                {"values": {"x": "a"}}""").contains("blocks"));
    }

    @Test
    void numbersYesNoAndText() {
        ReaderValue number = new ReaderValue(ReaderPath.parse("a").getOrThrow(), "", ReaderType.NUMBER, 0.001);
        assertEquals(3, number.number(IntTag.valueOf(2500)), "2.5 rounds to 3");
        assertEquals(2, number.number(DoubleTag.valueOf(1999.0)));
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("x"));
        assertEquals(1, ReaderValue.number(ReaderPath.parse("a").getOrThrow()).number(list), "a list counts");
        assertEquals(Long.MIN_VALUE, number.number(StringTag.valueOf("12")), "text is not a number");
        ReaderValue yes = new ReaderValue(ReaderPath.parse("a").getOrThrow(), "", ReaderType.YES_NO, 1);
        assertEquals(1, yes.number(ByteTag.valueOf(true)));
        assertEquals(0, yes.number(IntTag.valueOf(0)));
        assertEquals(1, yes.number(StringTag.valueOf("true")));
        assertEquals("fast", ReaderValue.text(StringTag.valueOf("fast")));
        assertEquals("12", ReaderValue.text(IntTag.valueOf(12)));
    }

    @Test
    void laterFilesOverrideEarlierOnesByName() {
        PackReader base = parse("""
                {"blocks": ["mod:a"], "values": {"stored": "Base.Stored", "capacity": "Base.Cap"}}""");
        PackReader pack = parse("""
                {"blocks": ["mod:a"], "values": {"stored": "Pack.Stored", "extra": "Pack.Extra"}}""");
        Identifier first = Identifier.parse("maxtech_storage:mod/a");
        Identifier second = Identifier.parse("zpack:a");
        List<PackReaders.Merged> merged = PackReaders
                .merge(List.of(new PackReaders.Entry(first, base), new PackReaders.Entry(second, pack)));
        assertEquals(List.of("capacity", "stored", "extra"), merged.stream().map(PackReaders.Merged::name).toList(),
                "grouped by reader: the first keeps what was not overridden");
        assertEquals(second, merged.get(1).reader());
        assertEquals("Pack.Stored", merged.get(1).value().path().text());
        assertEquals(first, merged.get(0).reader());
    }

    @Test
    void applySortsByFileId() {
        PackReader reader = parse("""
                {"blocks": ["mod:a"], "values": {"x": "a"}}""");
        int before = PackReaders.generation();
        PackReaders.apply(Map.of(Identifier.parse("b:z"), reader, Identifier.parse("a:z"), reader,
                Identifier.parse("b:a"), reader));
        assertEquals(List.of("a:z", "b:a", "b:z"),
                PackReaders.all().stream().map(entry -> entry.id().toString()).toList());
        assertTrue(PackReaders.generation() > before, "a reload bumps the generation");
        PackReaders.apply(Map.of());
    }
}
