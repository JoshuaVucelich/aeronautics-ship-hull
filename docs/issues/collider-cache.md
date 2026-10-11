# Cache hull bounds and batch collider replays

Every block edit on a ship called `recomputeHullBounds`, which walks the whole voxel set. A 457-block ship that loses a chunk of interior blocks (a blast, a build) did that once per block. Waking a soft-slept ship then replayed each deferred cell through Rapier's `changeBlock`, which updates the cell and its six neighbors natively.

Block-state collider *shapes* are already memoized inside `RapierVoxelColliderBakery`. This change does not add a second shape cache.

## Change

- Expand the hull AABB when a block is added. Removing an interior block only deletes the voxel. A full scan runs when a block on the current min/max face is removed, or when the cache is turned off.
- Interactive-cell classification (chest, seat, bearing, engine) is cached per block state.
- If a wake replays at least 48 deferred cells, each dirty 16³ section is uploaded once via `handleChunkSectionAddition` from the current chunk data.

`/shiphull colliderCache off|on` is the A/B switch.

## Parity

The hull AABB matches a full scan after every edit: adds expand it, interior deletes cannot shrink it, face deletes recompute it. Section replay reads the chunk's current block states, which already include the edits that were deferred. Items are untouched.

## Not in this change

Rapier stepping, contraption pose uploads, block-entity ticks, and network pose packets.
