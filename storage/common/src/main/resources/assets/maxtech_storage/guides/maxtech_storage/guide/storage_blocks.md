---
navigation:
  title: Storage Blocks
  icon: maxtech_storage:storage_block_tier_1
  position: 98
item_ids:
  - maxtech_storage:storage_block_tier_1
  - maxtech_storage:storage_block_tier_2
  - maxtech_storage:storage_block_tier_3
---

# Storage Blocks

A storage block is a [drive](cells.md) and one cell in a single block. It holds many item and fluid kinds, counted in
bytes as in a cell, and joins the network like any device. For one kind in huge numbers, use a
[bulk drive](bulk_drives.md) instead.

<BlockImage id="maxtech_storage:storage_block_tier_1" scale="3" />

Tier I holds <Config key="cells.storageBlockBytes.tier1" unit="bytes" />, tier II
<Config key="cells.storageBlockBytes.tier2" unit="bytes" /> and tier III
<Config key="cells.storageBlockBytes.tier3" unit="bytes" />. Use one with an empty hand to see how full it is and to
set its priority. Broken or picked up, it keeps everything it holds; the item shows
the bytes used and the kinds. Like a cell, an item that despawns, falls into the void or is cleared takes its
contents with it. Add-ons and filters are not available for storage blocks.

<RecipeFor id="maxtech_storage:storage_block_tier_1" />
<RecipeFor id="maxtech_storage:storage_block_tier_2" />
<RecipeFor id="maxtech_storage:storage_block_tier_3" />
