---
navigation:
  title: Pattern Providers
  icon: maxtech_storage:pattern_provider
  position: 101
item_ids:
  - maxtech_storage:pattern_provider
  - maxtech_storage:pattern_provider_part
  - maxtech_storage:crafting_assembler
  - maxtech_storage:crafter
  - maxtech_storage:generator_provider
---

# Pattern Providers

A <ItemLink id="maxtech_storage:pattern_provider" /> holds [patterns](patterns.md) and runs them on the block it faces
(as a cable part, the block beyond the face). Its patterns become craftable on the network.

<GameScene zoom="3" interactive>
<ImportStructure src="providers.nbt" />
<DiamondAnnotation pos="1.5 1 1.5" color="#ff00e5ff">
Provider on a crafting assembler
</DiamondAnnotation>
<DiamondAnnotation pos="2.5 1 1.5" color="#ff00e5ff">
Provider part on a furnace
</DiamondAnnotation>
<DiamondAnnotation pos="3.5 0.5 1" color="#ff00e5ff">
Crafter
</DiamondAnnotation>
</GameScene>

<RecipeFor id="maxtech_storage:pattern_provider" />

It pushes a batch's inputs into the machine, then pulls the outputs back into the network. A machine may also push
results into an [interface](interface.md). **Blocking mode** (on by default) holds the next batch until the machine is
empty. Nine pattern slots; research adds up to <Config key="crafting.providerExtraSlots" /> more. With several
providers for a pattern, <Config key="crafting.dispatchStrategy" /> splits the batches.

## Lock modes

Set in the **Mode & redstone** tab, on top of blocking mode:

- **None**: pushes whenever the machine takes a batch.
- **Locked with signal**: pushes nothing while the provider gets a redstone signal.
- **Locked without signal**: pushes only while it gets a signal.
- **Until redstone pulse**: pushes one batch, then waits for a pulse.
- **Until result returns**: pushes one batch, then waits until its main output is back in the network (through the
  provider or an [interface](interface.md)).

A locked provider takes no work: a job uses another provider with the pattern, or waits and shows "Provider locked"
as its stuck reason. The crafter has the same modes.

## Crafting patterns

Crafting-table patterns need a <ItemLink id="maxtech_storage:crafting_assembler" /> in front of the provider, or a
<ItemLink id="maxtech_storage:crafter" />. Each assembler craft costs
<Config key="crafting.assemblerEnergyPerCraft" unit="MU" />.

<RecipeFor id="maxtech_storage:crafting_assembler" />

## Crafter

A <ItemLink id="maxtech_storage:crafter" /> is a provider and assembler in one block: it holds nine crafting,
stonecutting or smithing patterns and crafts them itself when a job needs them. Each craft costs
<Config key="crafting.crafterEnergyPerCraft" unit="MU" />. With the same pattern in several places, the higher pattern
priority gets the work first. The crafter and the assembler show the item they craft behind their glass.

<RecipeFor id="maxtech_storage:crafter" />

## Crafter manager

In a terminal's pattern overview, **Manage providers** lists every pattern provider on the network; search by label or
by what its patterns make. Pick one to see its pattern slots and move patterns in and out as in its own screen. A
pattern held on the cursor goes into the next provider you pick. Changing a provider needs full
[access](security.md) to it. Providers in unloaded chunks are not listed.

## Generator provider

A <ItemLink id="maxtech_storage:generator_provider" /> faces a machine that makes something from nothing, such as a
cobblestone generator. Set the output in its screen; the network then counts it as craftable. It gives a redstone
signal while a job needs the output and pulls what arrives. Face it at the machine itself, not a shared chest.

<RecipeFor id="maxtech_storage:generator_provider" />
