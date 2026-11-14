# Coal Vein map-generator handoff — WurmRail 2.29.0

- **Cave tile ID:** unsigned **208** (`0xD0`; Java signed byte **-48**).
- **Constants:** `org.wurmrail.shared.CoalVeinTiles.TILE_ID` / `TILE_TYPE`.
  Registered runtime enum: `Tiles.Tile.TILE_WURMRAIL_COAL_VEIN`.
- **Cave layer:** write tile type 208 into **`map_cave.map`**, preserving the
  tile's intended cave-floor height and cave-clearance data byte:

  ```java
  int oldTile = caveMesh.getTile(x, y);
  caveMesh.setTile(x, y, Tiles.encode(Tiles.decodeHeight(oldTile),
          (byte) 208, Tiles.decodeData(oldTile)));
  ```

- **Resource layer:** write the initial mining count into the **upper 16 bits**
  of the corresponding **`resources.map`** cell, preserving its lower 16 bits:

  ```java
  int oldResource = resourceMesh.getTile(x, y);
  resourceMesh.setTile(x, y, (oldResource & 0x0000ffff) | (count << 16));
  ```

- **Valid starting count:** **1–65,534**. One count is one successful Coal
  mining action. Do not use **0** (exhausted) or **65,535** (native unset sentinel).
- Save both layers. **No additional map layer, sidecar file or item placement
  is required.** Do not replace vanilla vein IDs, overwrite the resource cell's
  lower 16 bits, alter the cave geometry, or hard-code a Coal item-template ID.
- Install **WurmRail 2.29.0 server and client support before running/joining a
  coal-generated world**. An offline generator may write numeric tile ID 208
  directly; the new enum member exists only after WurmRail registration.

The map generator itself is unchanged by this release.
