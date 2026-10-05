# Custom items and blocks

Registered namespaced items and synchronized shaped/shapeless or furnace recipes are discovered automatically by the initial adapter. An item being present in a registry does not reveal where it drops or how a custom machine works.

For an ordinary mineable custom block, copy [the example](../examples/lodekeeper-sources.json) to your Fabric `config/lodekeeper-sources.json`, edit its identifiers, and set `enabled` to `true`. Join a world after editing. The optional `tools` list describes alternative harvest tools; actual suitability, reach and block breaking still use Minecraft's normal rules. Commands use the exact registered item, for example `!lk get example:ruby 16`.

Each source supplies its own ID, output item and candidate blocks. The file is capped at 256 KiB and 256 sources. Missing items/blocks and invalid records are rejected. Gather completion counts observed inventory, so a supplied drop contract does not fabricate items or treat a mined block as guaranteed output. Server loot changes can invalidate the contract and cause a bounded failure.

Specialty machines, custom screens, entity drops and nonstandard physics need execution providers. The pure core's `CustomSource` describes those goals, but the initial Fabric executor does not yet implement a public machine-provider runtime. These mechanics remain pending rather than implied by registry discovery.
