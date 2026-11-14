# WurmRail 2.30.3

[Download the bridge update](https://github.com/outsider512/WurmRail/releases/tag/v2.30.3). The 2.30.3 server works with the 2.30.2 client.

A steam railway for Wurm Unlimited: build tracks, couple passenger and cargo
wagons, drive with friends, or run repeating station routes with signals and
automatic junction control. Normal play uses right-click menus; commands and
item IDs are optional.

Start with [QUICKSTART.md](QUICKSTART.md). See [CHANGELOG.md](CHANGELOG.md) for
release changes and [THIRD-PARTY.md](THIRD-PARTY.md) for dependency notices.

## What is included

- Steam locomotives and general cargo wagons with shared cargo and four passenger
  seats per vehicle. The default maximum is eight vehicles per train.
- Eight compass directions, straight and diagonal track, 45- and 90-degree
  bends, physical bidirectional junctions, and surface-to-cave railways.
- Manual power, stop, reverse and speed controls. Forward and reverse have the
  same acceleration and speed limit: 5 m/s by default.
- Combined stations that drop off passengers and pick up waiting players.
  Each passenger can select a destination independently.
- Freight stops on normal routes, with timed loading/unloading of real items
  between nearby storage and attached cargo wagons.
- Bulk Hoppers for approved loose resources, with high-capacity real inventories
  and contents/remaining-capacity inspection.
- Steam Rock Drills that slowly mine selected neighbouring cave faces into a
  linked Bulk Hopper using real resources and finite native vein depletion.
- Distinct underground Coal Veins yielding real mineral Coal, with finite
  resources and ordinary bulk storage/general-wagon freight support.
- Steam Water Pumps collecting natural water into Industrial Water Tanks, and
  5,000-litre Tank Cars served by timed stored-water LOAD/UNLOAD freight stops.
- Shared fuel and water supply links, saved internal buffers and work-based
  consumption for Rock Drills, Yarders and fuel-powered Water Pumps.
- Steam Yarders that harvest and replant a small circular woodlot into
  timber-only Log Bunks, with ordinary Wurm tree growth and real timber items.
- Specialized Log Cars for logs and felled trees, with industrial weight/volume
  capacity and normal freight-stop loading and unloading.
- Repeating automatic routes, protected red/green signals, fair batches over
  shared single track, station queues and coordinated recovery maneuvers.
- Saved tracks, trains, cargo, routes and service settings. Running scheduled
  services resume after restart; explicitly stopped trains stay stopped.
- Original models, local client support and Windows installers. No model web
  server or additional network port is required. Locomotives do not consume fuel.

## Requirements and release packages

Install the server mod on the **server computer** and the client support/model
pack on **every player's computer**. Installing one does not install the other.

This release is built and checked against Wurm Unlimited 1.9.1.5, Java 8,
[WurmServerModLauncher v0.47](https://github.com/ago1024/WurmServerModLauncher/releases/tag/v0.47)
and [WurmClientModLauncher v0.15](https://github.com/ago1024/WurmClientModLauncher/releases/tag/v0.15).
The optional installer downloads use pinned hashes. Wurm Unlimited and the
modloaders are not bundled; use your own licensed game installation.

| Archive | Contents | Use |
| --- | --- | --- |
| `WurmRail-2.30.3.zip` | Server mod, client support/model pack, both installers and guides | First installation or sharing the complete release |
| `WurmRail-2.30.3-server.zip` | Server mod, server installer and guides | Server installation/update |
| `WurmRail-2.30.3-client.zip` | Client support/model pack, client installer and guides | Installation on each player's PC |

Install the matching server and client updates to use bridge rails.
Every player needs the client update for deck-conforming track models.
Fully close and restart Wurm after updating. Preserve a complete world backup
before upgrading.

The client keeps the locomotive and following cars on the same playback
timeline during slow game ticks and native visibility/metadata refreshes.
Forward, reverse, curves, terrain tilt and rider cameras use the original
authoritative positions; no visual coupling offsets are manufactured.

## Windows installation

Extract the release outside the game directories. Replace the example paths
below with your actual installation directories. Run each installer locally on
the computer it is intended to change.

### Server

1. Stop the server and its launcher. Back up the selected world/database
   directory, including its complete `wurmrail` folder.
2. Open PowerShell in the extracted release folder. `ServerDir` must contain
   `server.jar` and `common.jar`.
3. Run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\Install-WurmRail.ps1 -ServerDir 'D:\Games\Wurm Unlimited\WurmServerLauncher'
   ```

   Add `-InstallModLoader` if that server has no working server modloader.
4. Review `mods/wurmrail.properties`, then start `modlauncher.bat` from the
   server directory. The normal unmodified server launcher does not load mods.
5. Check the server log for successful WurmRail loading before joining.

### Every player's client

1. Fully close Wurm Unlimited and its launcher.
2. Open PowerShell in the extracted full or client release. `ClientDir` must
   point to `WurmLauncher`, containing `client.jar` and `common.jar`.
3. Run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\Install-WurmRailClient.ps1 -ClientDir 'D:\SteamLibrary\steamapps\common\Wurm Unlimited\WurmLauncher'
   ```

   Add `-InstallModLoader` if that client has no working client modloader. This
   downloads the checked official loader and runs its client patcher.
4. Start Wurm Unlimited through Steam normally. Repeat on every player's PC.

Both installers back up replaced files under `wurmrail-backups/<timestamp>/`
inside the target installation and roll back a failed copy. Use `-WhatIf` to
preview changes. An existing server configuration is retained;
`-ReplaceConfig` explicitly replaces it with the supplied defaults. A legacy
default `maxSpeed=3` is upgraded to 5 while custom caps are retained.
Installers do not copy world data or proprietary game JARs from the release.

## Build and drive your first train

Prepare a clear, dry rail bed. For the first route, use a straight line with
ample room for the entire train at both ends.

1. Craft **rail track sections** from a plank and an iron band under
   construction materials. Follow Wurm's creation window for remaining parts.
2. Carry the finished sections, right-click the starting ground tile, and choose
   **Build railway**. Select a compass direction and length, then **Build track**.
   Each tile consumes one section. Nearby clear rails connect automatically.
3. Craft a rail locomotive under **Carts**, starting with a plank and an iron
   band. Complete it on connected rail, or open **Train controls → Place on
   track** to choose nearby track. Registration is automatic.
4. Craft a wagon behind the locomotive. Use **Couple rail wagon** on the wagon,
   or select a nearby wagon in the locomotive's **Train controls**.
5. Use **Embark Passenger**, or **Board** in the train window, to take a free
   seat. Friends can ride on any permitted vehicle in the consist.
6. Use **Power on**, **STOP**, **Reverse manually**, and **Crawl / Cruise / Full**
   in **Train controls**. **Full** selects the configured server cap. Wurm's
   normal WASD steering is disabled for these rail vehicles.

Stop and power off before reversing, changing couplings or editing routes.
Everyone must disembark before coupling changes. Use Wurm's normal disembarking
action to leave. **Reverse manually** backs the train without spinning the
locomotive, clears its automatic route, and leaves power off.

Vehicles have four-metre spacing along the track. Leave connected rail behind
the engine for every wagon. GMs with power 2+ build without materials and can
create vehicles from the building window.

Cargo wagons have a **10,000 kg payload**, **100 item stacks** and **5,904 litres**
of space. The first reached limit ends loading. Existing wagons use the updated
template automatically; no replacement is needed. **Train controls** shows actual
weight, stack and volume usage. The locomotive retains its separate 300 kg hold.

## Junctions and track editing

Right-click a rail and choose **Railway track** to inspect or edit its connected
compass sides. New construction supports 45- and 90-degree turns; sharp
135-degree turns are not offered. An intended diagonal can be connected
explicitly; automatic linking avoids cutting across an existing cardinal bend.

A junction stores a **physical alignment between two connected sides**.
**North ↔ East** works from north to east and east to north; it blocks other
approaches. The pair does not change meaning with train heading.

Use **Cycle junction alignment** for a direct switch interaction, or choose a
specific pair in **Railway track → Junction alignment**. Invalid pairs are
skipped. The low track indicator shows the connected pair; the red mechanical
lever remains beside the switch. **Not aligned** blocks every approach.
Automatic trains request the alignment they need and lock it until the entire
consist clears. Occupied or reserved points cannot be changed manually.

Use **Repair track model** if a rail's display item is missing. Remove unused
track from the same window to recover one track section. Unsafe edits to
occupied or reserved track are refused. Line construction checks the whole
request before consuming materials.

## Stations, passengers and cargo

Build one combined **Station** tile on clear ground directly north, east,
south or west of a rail. Choose **Build station**, select the adjoining rail
and give the station a name. A station costs one track section; diagonal
neighboring tiles do not count. Keep the station area level and unobstructed.

Stand on its tile to wait. A Links status badge and the **Station** window show
your waiting state. Boarding or leaving the tile clears your queue entry.
Players just dropped off must leave the tile before waiting there again; each
player's state is independent.

Select a connected destination in **Station**, **Choose station destination**
on a vehicle, or **My destination** in train controls. The train unloads selected
passengers before picking up eligible waiting players into free seats. A full
or inaccessible car leaves you waiting. Destinations do not change the driver's
route or create a detour.

Passenger service brings the rider's actual car beside the stop. Leave enough
rail beyond it for rear wagons to reach the station before a terminal or
turnaround. Blocked landing ground leaves the passenger aboard with their
destination retained. Passenger service never overrides an operator's STOP.

For cargo, stop beside a vehicle and use normal **Open**, **Load** and **Unload**
actions. The train window can select a specific vehicle's hold. Wurm's weight,
capacity, locks, keys and access permissions apply to boarding and cargo.

## Automated freight

1. Put an ordinary chest, barrel, Bulk Hopper or Log Bunk within **8 metres** of
   the intended freight rail, on the same ground level. Unlock and unseal it.
2. Right-click the rail → **Freight stop**. Enter a name such as **Mine Loader**,
   select the storage, **LOAD**, and **Any cargo** or an exact item template ID.
   Choose a rate, initially **100 kg/s**, and save.
3. Configure a second rail as **Foundry Unloader**, with **UNLOAD** and its own
   storage. Use dedicated freight rails outside junction points; passenger
   stations and named train stops use separate rail tiles.
4. Attach an unlocked compatible cargo wagon or Log Car. The stop, storage, train and wagon must have
   the same owner in this first version. Services also run while that owner is
   offline. Normal land and storage permissions apply when configuring a stop.
5. In **Train controls → Set up train route**, add **Mine Loader → Foundry
   Unloader** and choose **Save and start**. The normal route repeats.

The train stops through the existing station system, transfers cargo at one-second
intervals, then continues when no matching cargo remains or capacity prevents
further transfers. A heavy item waits until enough weight allowance has accrued.
Items move whole, retaining their IDs, quality, damage, enchantments and contents;
they are not split or converted to virtual cargo. A container's contents count
toward its transfer weight. STOP pauses servicing; saved pending arrivals resume
with a fresh allowance after restart, using the actual inventories as truth.

General wagons handle ordinary solid freight; Log Cars accept only raw round
timber. Native bulk bins/crates, liquids, locked or sealed cargo, and special
restricted items are excluded from generic freight. Liquid Tank Cars use water
service stops. Each stop stores its own rate for future loader types.
Missing or locked storage completes the visit with a reason;
an inventory recovery fault stops the train for operator attention. Read its
Status and the **Freight stop** window before restarting it.

## Bulk Hoppers

Craft **bulk hopper** under **Containers**, starting with a plank and an iron
band. Complete its iron-sheet, plank, band and nail requirements. The heavy
container is built on the ground and uses native owner movement and Open.

Each hopper holds up to **100,000 kg**, **64,000 litres** and **4,096 real item
stacks**; whichever limit is reached first applies. It accepts only the explicit
loose-resource whitelist in `BulkHopperPolicy`: ores, coal/charcoal, stone and
related shards, dirt, sand, clay, peat, flint, ash and rock salt. Tools, weapons,
armor, parts, containers, food, liquids, logs and other crafted items are rejected.
Items retain their original identities and properties.

Right-click **Bulk hopper status** to inspect grouped contents, total weight,
remaining weight/volume and item slots. Native **Open** handles normal loading
and removal. Owner and Wurm access rules apply. Link an unlocked, unsealed
hopper in **Freight stop** exactly like other trackside storage, within 8 metres
on the same ground level and with the same owner as the train and stop. LOAD
moves eligible resources into existing cargo wagons; UNLOAD rejects anything
outside the hopper's whitelist. The hopper uses no virtual bulk-resource totals.

Future machines should use `BulkHopperPolicy.canInsert` and the guarded native
item insertion APIs. The allowed resource list is centralized for extension.

## Tank Cars and stored-water freight

Craft **rail tank car** under **Carts**, starting with a plank and iron band.
Complete its axles, iron sheets, bands, planks and nails. Couple it like an
ordinary cargo wagon. Each Tank Car holds **5,000 litres / 5,000 kg of water**
in one real variable-weight Wurm item. Solid cargo is rejected. Water is the
only supported liquid for now; storage policy remains independent of loaders.

Tank Cars use existing consists, movement, ownership, locks, routes and four
end-platform passenger seats. **Train controls** reports liquid capacity.
Native **Open** and pouring provide manual access. Fresh/salt water, rarity and
differing native liquid identities remain separate; empty incompatible water
before switching sources. Existing cars containing more than the new capacity
retain every litre and can unload normally. They cannot accept more water until
there is room under the new limit. Capacity is not currently a configuration key.

## Industrial Water Tanks and Steam Water Pumps

Craft **industrial water tank** under **Containers**, starting with a plank and
iron band. Finish its sheets, planks, bands and nails on the ground with
**4.5 metres of headroom**, including in caves. It stores **100,000 litres /
100,000 kg** of water in one native liquid item. **Industrial water tank status**
shows quantity, remaining capacity and water identity. Contents persist through
the native item database; there is no virtual water stock.

Craft **steam water pump** under **Tools**, beginning with a plank and iron band.
Finish its eight planks, eight iron sheets, six iron bands and four large nails.
Place it on the ground with **2.2 metres of headroom**. Surface and open cave
water sources work; solid walls, dry tiles, lava and tar are not natural water.

1. Place the pump within **8 metres** of a valid natural water source.
2. Place an owned, unlocked and unsealed Industrial Water Tank within
   **16 metres**, on the same ground level.
3. Right-click the pump → **Steam Water Pump controls**. Select its exact water
   source and linked tank, then choose **Start pumping**.
4. Read its status in the same window. **Stop pumping** stops it manually.

The configurable default is **100 litres per second**, applied gradually with
no offline catch-up or saved transfer backlog. The pump pauses when its tank is
full, missing, moved, locked or otherwise unavailable, or when its source is no
longer valid. Its running intent remains saved and it automatically retries;
normal-rate pumping resumes when capacity and access return. Manually stopped
pumps remain stopped through restart. Native inventory faults quarantine the
water tank for inspection rather than repeatedly risking water accounting.

The pump consumes fuel according to the actual volume pumped. Its natural
source supplies its own boiler water, so it has **no separate Water Supply
link**. Link Fuel Supply in the pump window. No fire, heating, pressure,
maintenance or detailed steam generation is simulated. Locomotives remain
independent of fuel and water in this release.

### Load and unload Tank Cars

The supply chain is:

**Natural water → Steam Water Pump → Industrial Water Tank → Tank Car →
destination Industrial Water Tank → future equipment**

1. Place each owned, unlocked Industrial Water Tank within **8 metres** of its
   dedicated freight rail on the same ground level.
2. Right-click the source rail → **Water freight stop**. Select **LOAD from
   tank**, its Industrial Water Tank, a name and a litres-per-second rate.
3. Configure the destination rail through the same action, selecting **UNLOAD
   into tank** and its destination storage.
4. Add these two named stops to the normal train route and start service. Stops,
   tanks, train and Tank Cars must share an owner.

The default **100 L/s** is one allowance shared across the train, so an empty
5,000-litre car takes about **50 seconds**. Railway stops only transfer existing
stored water; they never create it or draw directly from natural tiles. STOP
pauses transfers. Restarted visits earn a fresh allowance. Full cars, empty
sources, full destinations, incompatible water and unavailable storage finish
cleanly so the normal route can continue. Solid-cargo cars are skipped.

Verified rollback restores both liquid inventories after a failed transfer.
Uncertain contents stop service for inspection. After manual emptying and
inspection, an owner or GM can use **Clear empty liquid tank fault**; this action
never deletes water.

### Deprecated natural-water rail stops

Old Water Fill Station definitions load only as inactive legacy waypoints.
They create no water and are unavailable for new construction or route setup.
Existing routes retain valid track identities and pass safely through the old
location. Replace these stops in routes with stored-water LOAD freight stops.
The legacy definitions are retained for safe world loading, without changing
native tank contents. Existing Water Unload Stops migrate as **UNLOAD** water
freight stops with their names and tank links preserved.

### Future consumer interface

`MachineWaterSources` provides explicit nearby-tank selection and immutable
`Link(consumerId, tankId, ownerId)` values for future equipment to save with its
settings. Links allow **16 metres** (four tiles) on the same ground level and
revalidate ownership, locks, native contents and current range on each use.
Selection searches nearby tiles only when requested; there is no world polling.

Call these APIs on the server poll thread:

```java
MachineWaterSources.Link link = MachineWaterSources.link(actor, machine, tank, ownerId);
MachineWaterSources.Availability supply = MachineWaterSources.available(link);
LiquidTankTransfers.Result receipt = MachineWaterSources.request(link, requiredMillilitres);
```

Availability reads the real water quantity and salinity. Exact requests remove
nothing when insufficient water exists. Consumers must check the returned actual
volume and halt on `requiresOperatorIntervention`, without blindly retrying an
uncertain result. `LiquidTankTransfers` also supplies conservative native
container-to-container transfers. Steam machine supply buffers now draw real
water through the shared supply controller; other future consumers can reuse
these checks.

## Steam machine supplies

Rock Drills and Yarders use three independent links: **Fuel Supply**, **Water
Supply**, and their existing **Output Storage**. Water Pumps use **Fuel
Supply**, a valid natural source and their destination Industrial Water Tank;
they do not need a separate boiler-water tank.

Choose owned, accessible supply storage within **16 metres** on the same
ground level, then save or start the machine. The range is configurable.
Supply links are explicit saved item IDs. Nearby storage is searched only
when configuring a link; running machines check those selected objects.
Keep supply storage unlocked, unsealed, placed on the ground and under the
same owner as the machine. Fuel and output may be the same container.

Approved fuels are **mineral Coal**, **charcoal**, **logs**, **felled trees**,
**kindling** and **wood scraps**. Tools, crates, furniture and other crafted
wooden goods are not fuel. Bulk Hoppers are useful for Coal/charcoal; Log Bunks
or ordinary compatible storage can supply raw wood. When several fuels are
present, the highest configured energy per kilogram is used first: Coal,
then charcoal, then wood with the defaults.

Fuel has configurable energy per kilogram: wood **1**, charcoal **3**, Coal
**6** by default. Default work costs are:

| Machine | Fuel energy | Water |
| --- | --- | --- |
| Rock Drill | 10 per completed mining action | 10 litres per action |
| Yarder | 10 per harvested/replanted tree | 5 litres per tree |
| Water Pump | 0.02 per litre actually pumped | Its natural source; no separate tank draw |

Enabled equipment does not consume supplies merely for being switched on.
No eligible tree, no mineable face, full output storage and a full pump
destination do not spend work credits. A Yarder waiting for Wurm's ordinary
tree growth therefore remains idle. No offline production or consumption
backlog accumulates.

Each machine has modest saved buffers: **60 fuel-energy units** and, for
Drills/Yarders, **100 litres of water** by default. Filling them removes real
fuel mass and real tank water. Internal energy represents fuel already supplied;
it is not newly generated cargo. Work spends buffer credit only for its actual
action or pumped volume. No continual idle refill or hot-idle burn is added.
The buffers are deliberately small compared with external industrial storage.

An enabled machine reports **NO FUEL**, **NO WATER**, **OUTPUT FULL**,
**NO OUTPUT STORAGE**, or **NO VALID WORK** when it must wait. It retries
automatically and resumes when deliveries, output capacity or useful work
return. **STOPPED** is an explicit operator stop and stays stopped through
restart. Native inventory/accounting faults require inspection instead of
unlimited retries. Existing machine settings remain; after upgrading, select
the new supply links before expecting production.

Paid buffers and unfinished supply transactions are saved in
`wurmrail/steamSupplies.properties`. An interrupted work action stops for
inspection; check retained output, then **Save settings** to acknowledge the
recovery before starting again. Corrupt or missing primary supply state with
an existing backup disables steam production. Backups are retained for
inspection and are never automatically replayed as spendable balances.

To bootstrap a Coal mine, stock a wood-fuel container, fuel a Water Pump from
it, and fill an Industrial Water Tank. Link the Rock Drill to wood fuel and
that water tank. Once Coal reaches its output Bulk Hopper, the same hopper
can become its Fuel Supply. Drilling then consumes some Coal while adding
new real Coal. Water deliveries remain necessary.

Locomotives do not use this system yet. There is no boiler temperature,
pressure, heating/startup time, pilot flame, priming, explosion, lubrication,
wear, maintenance or coke processing.

## Coal Veins and mineral Coal

Coal Veins are distinct underground resource walls. Coal is a real physical
item, separate from vanilla **charcoal**. This Wurm Unlimited version contains
no separate mineral Coal item, so WurmRail supplies a stable `wurmrail.coal.v1`
template while leaving charcoal unchanged. Coal reuses the installed game's
coal/charcoal icon and model; it does not replace that game's item template.

Each successful mining action yields a normal **20 kg / 20 litre** Coal item
and consumes one unit of the finite native cave-resource count. Coal Veins are
supported by the Steam Rock Drill's normal cardinal-face selection and linked
Bulk Hopper output. Normal protection, settlement permissions and storage
backpressure apply. Steam drilling now requires fuel and water; a Coal Hopper
may be selected as both output storage and Fuel Supply after bootstrapping.

Exhausting Coal turns that tile into an ordinary **rock wall**, preserving its
cave geometry. It does not automatically open a tunnel or renew the coal count.
An enabled drill may subsequently mine ordinary rock under its existing safe
excavation limit. The Coal Vein does not become an infinite coal source.

Bulk Hoppers accept Coal and charcoal as separate resources. Existing general
cargo wagons carry Coal; Log Cars and Tank Cars reject it. Configure ordinary
freight stops to LOAD a source Bulk Hopper and UNLOAD into destination storage,
then add both names to the normal train route. Choose **Coal** in the freight
filter dropdown to match it independently of charcoal; no numeric template ID
is needed. The existing specific-template filter also supports it. Storage and freight retain the same
actual item ID, weight, material, quality, damage and rarity.

No existing world is seeded with Coal Veins by this update. Map generation,
distribution, seam sizes and rarity are intentionally unchanged. See
[COAL-MAP-HANDOFF.md](COAL-MAP-HANDOFF.md) for exact placement bytes and counts.

## Steam Rock Drills

Craft **steam rock drill** under **Tools**, starting with a plank and iron band,
then complete its iron sheets, planks, bands and nails. Build it on an open, dry
underground tile beside natural mine walls. Its four heads face north, east,
south and west; diagonals and more distant walls are excluded.

1. Place an owned, unlocked Bulk Hopper within **8 metres**, on the same cave
   level. Unlock and unseal both the hopper and drill.
2. Right-click the drill → **Select drill Bulk Hopper**. Choose the hopper.
3. Enable the available cardinal faces in the same window. It shows the resource
   type or the reason a face is unavailable. Choose **Save settings** or **Start mining**.
4. Use **Steam rock drill status** to inspect the selected next face, enabled
   faces, linked hopper, running state and stop reason. **Stop drill mining**
   works directly from the item menu.

One action takes **30 seconds** by default and produces one normal-sized Wurm
resource item. Enabled available faces rotate in **North → East → South → West**
order, skipping disabled or depleted faces. The drill consumes the native vein
resource counter or rock mining-state allowance. It stops before opening a new
tunnel tile; manually advance the mine when the selected faces are exhausted.
Reinforced, clad, blocked, protected or inaccessible faces are excluded.

The machine's quality acts as its mining skill, with native mining difficulty
and vein quality caps. This testing version grants no player skill gains and
does not produce bonus gems. Each completed mining action consumes the
configured fuel energy and water. Link **Fuel Supply** and **Water Supply** in
the drill controls. No fire, steam pressure or maintenance is simulated.
Production continues with its owner offline when settlement
mining permissions allow it. No work accrues while the server is stopped.

Missing, locked, moved, incompatible or full output storage pauses enabled
mining before another cut. Missing fuel or water also pauses it; supply delivery
allows automatic resumption without pressing Start again. If native insertion unexpectedly fails after a cut, its exact real
item remains in the drill's one-item safety compartment. Clear the hopper and
**Start mining** to retry delivery first; native **Open** allows owner recovery.
An unconfirmed mining result stays retained for operator inspection. Move a
drill only while stopped, then save its hopper and face settings at the new tile.
Control is restricted to its owner or a GM. The output hopper must share its owner.

Drill settings, running intent and the next face survive restart. A resumed drill
earns a fresh interval before working, with no instant catch-up. The shared
`MachineStorageLinks` checks can also support future machine-to-storage links.

## Steam Yarders and Log Bunks

Craft **log bunk** under **Containers**, beginning with a plank and an iron band.
Finish its planks, iron sheets, bands and large nails on the ground. It is an
open industrial timber rack with a real Wurm inventory: **100,000 kg**,
**64,000 litres** and **4,096 stacks**, whichever limit is reached first.
Only native **logs** and **felled trees** are accepted. Ore, dirt, liquids,
containers, tools and finished crafted items are rejected. **Log bunk status**
shows grouped contents, stored weight and remaining weight/volume capacity.
Use native **Open** for manual access. Freight stops can select a Log Bunk
as their LOAD or UNLOAD storage and service a coupled Log Car.

Craft **steam yarder** under **Tools**, beginning with a plank and iron band,
then finish its sheets, planks, bands and large nails. Place it on dry surface
ground outside buildings and bridges, with **3.3 metres of headroom**.

1. Place an owned, unlocked and unsealed Log Bunk within **8 metres**, on the
   same ground level. Unlock and unseal the Yarder too.
2. Right-click the machine → **Steam Yarder controls**. Select its Log Bunk,
   the species to replant and **Mature / Old / Very old / Overaged** harvest age.
   Also select Fuel Supply and a Water Supply Industrial Water Tank.
3. Choose **Start forestry**. **Save settings** stores the choices without
   running; **Stop forestry** also works directly from the item menu.
4. Inspect the same window for running state, woodlot centre, ready trees,
   pending timber, linked storage and the current waiting or stop reason.

The managed area is a fixed **five-tile-radius circle** centred on the Yarder,
using tile centres. Its own tile is excluded. At most one eligible tree is
harvested per **30-second** interval, cycling through the managed tiles. Timber
uses the native tree species, age, resource template and weight rules; Yarder
quality acts as its woodcutting skill. The harvested tile is replaced with a
young sprout of the selected species. Wurm alone advances its age. Bare tiles
are not automatically planted; establish the initial woodlot normally.

Protected trees, enchanted trees, railway tiles, buildings, bridges, beehives
and unsuitable planting ground are skipped. On deeds and perimeters, the owner
must have permission to cut the tree's age and plant sprouts. Younger trees
stay standing while the machine waits for normal growth. No trees outside
the circle are harvested. Each actual harvest/replant consumes configured
fuel energy and water. Waiting for young trees consumes neither. No steam
pressure, maintenance or sprouts are required.

Missing, moved, locked, incompatible or full storage pauses enabled forestry
before another tree is cut. It resumes when output space and required supplies
are available again. If delivery fails after a confirmed harvest, its exact
timber item stays in the Yarder's one-item safety compartment. Clear the bunk
and **Start forestry** to retry delivery before another harvest. An unconfirmed
harvest remains retained for operator inspection. Use native **Open** for
manual recovery. Stop a Yarder before moving it, then save its settings again
at the new location. Control belongs to its owner or a GM; the bunk must share
the machine's owner.

Settings, next managed tile and running intent survive restart. The machine
earns a fresh interval after startup; no offline harvest backlog is produced.
`MachineTimberLinks` and `LogBunkPolicy` provide reusable storage checks for
future forestry machines. Dedicated Log Loaders and sawmills are not included.

## Log Cars

Craft **log car** under **Carts**, beginning with a plank and an iron band.
Finish its two small wheel axles, eight iron sheets, eight iron bands, sixteen
planks and four large iron nails. Couple it with **Couple rail wagon** like any
other wagon. It uses the existing four-metre consist spacing, movement,
reversing, ownership, routes and saved native item inventory.
For GM testing, right-click an empty existing rail tile, open **Railway track**,
and choose **Place Log Car here** under **GM vehicles**.

A Log Car accepts only native **logs** and **felled trees**, including smaller
trees that yield logs directly. Ore, dirt, coal, liquids, crates, tools, weapons
and finished goods are rejected through both normal insertion and automated
freight. Cargo keeps its actual item ID, wood material, quality, damage, rarity,
age metadata and weight. Use native **Open** for manual cargo access, or inspect
the selected vehicle's cargo capacity in **Train controls**.

Its payload limits are **20,000 kg** and **24,000 litres**; whichever is reached
first applies. Normal native timber volume fits about **300 logs** or **75 felled
trees** before volume fills. Heavy items may reach the weight limit sooner.
The much higher item safety limit is not the normal capacity constraint.

For an automated timber route:

1. Put the Yarder's Log Bunk within **8 metres** of a dedicated freight rail.
   Keep the storage, train, Log Car and stops under the same owner, unlocked
   and unsealed.
2. Choose **Freight stop → LOAD → Any cargo**, select the source Log Bunk,
   name it, and save. Any cargo handles both raw timber templates together.
3. Set another freight rail to **UNLOAD**, linked to a destination Log Bunk or
   another container that can accept the timber's native size and weight.
4. Add the two freight-stop names to the normal train route and start service.

The existing default **100 kg/s** allowance is shared across the train.
Transfers move whole items over time; a large felled tree waits until enough
allowance accumulates. Full or incompatible destination storage ends the visit
cleanly, retaining the timber aboard so the normal route can continue.

When a Log Car is attached, freight loads both timber forms into eligible Log
Cars, even if a general wagon is earlier in the consist. A full or unavailable
Log Car leaves timber in source storage instead of filling the general wagon.
Existing consists without a Log Car retain their earlier general-wagon timber
transport behavior. Tank Cars remain separate liquid inventories.

The cargo whitelist is shared by `RawTimberCargo`, with independent capacity
policies for Log Bunks and Log Cars. Future loaders and rolling stock can reuse
these checks. Log cranes/loaders, sawmills and other new car types are not
part of this release.

## Automatic service and signals

Build at least two connected stations. Named on-track stops created with
**Railway track → Make station** can also be used as automatic route stops.
Stop the train, open **Set up train route**, add stops in travel order, and
choose **Save and start**. The route repeats. Train routes favor continuing
forward and use backing when the layout requires it. Physical loops restore
engine-facing direction; a train cannot turn around on a single tile.

For shared track, place signals using **Railway signals** on a track or signal.
Choose the side the train approaches **from**. A **West approach** controls
trains entering from the western neighboring rail. Opposite travel requires
its own approach signal. Each signal costs one track section and refunds one
when removed; GMs build for free.

A three-way junction used from every side needs three approach signals; a
four-way junction needs four. Continue protection along the line, including
the track beyond the junction. Leave space for complete trains to clear points,
stops and signal boundaries.

**RED means stop or no route requested. GREEN is reserved for its named train
only.** The lamp shows the aspect. **Railway signals** shows the owner, protected
path and denial reason. All required junctions are aligned before authority
is granted; protection remains until the whole train clears. Keep power on
while waiting at red so the train can retry automatically.

Shared single track admits same-direction followers in fair batches before
changing direction for an opposing queue. A single line still needs passing
or turning space; trains cannot pass through each other. Automatic station
queues normally wait at the junction. An extra circuit is used when needed
to clear an earlier train or its return path.

A pull-in station can use its nearest suitable junction and a straight backup
lane to turn back. The lane must accommodate the consist plus twelve metres
beyond the points: a locomotive with two wagons needs at least twenty metres.
The station and backup arms must be opposite; the departure bend must be
supported. Occupied points and other train reservations remain protected.

Use **Railway services** for **Start all scheduled**, **Stop all scheduled** and
**Clear traffic jams**, and to inspect every listed train's Status and Traffic.
Bulk controls apply only to the services shown that you can operate. Jam
recovery asks running scheduled trains to coordinate safe yielding; it does
not restart operator-stopped or manual trains. Parked vehicles or insufficient
clearance may require a layout change or manual intervention.

## Terrain, tunnels and bridges

Straight rail permits **20 slope along the track and 0 slope across it**.
Twenty Wurm height units are two metres. Curves and junctions require a
completely flat tile. For a diagonal ramp, the two corners across the rails
must match: NE/SW corners for NW–SE track, or NW/SE corners for NE–SW track.
The largest corner-to-corner height difference must remain at most 20.

The client conforms rail meshes to the ground beneath them while retaining
their track connections. This includes transitions between grades and valid
diagonal ramps. Models do not reshape the ground: prepare a suitable bed.

Terrain rules apply to surface and cave floors. Caves need at least three metres
of clearance. Enter or leave caves on a straight cardinal connection through
an open entrance whose floor meets the outside ground. Clear trees and bushes
before laying track. Natural vegetation no longer grows on registered surface
rail tiles; removing rail restores normal growth there.

Buildings, walls, fences, closed doors, water, cliffs and lava can block
construction or movement. Terrain and edge obstructions are checked again as
trains move. Resolve an obstruction, inspect the train's Status, and power on
again if it stopped.

Use **Build railway** on a completed Wurm bridge deck or lay a line onto it
through a ground-level bridge exit. Wooden, stone brick, marble, slate, rounded
stone, pottery, sandstone and rendered brick decks support track. Rope bridges
reject track. The deck and approach must meet at the shared edge. On the deck,
track and vehicles use the native profile, including the grade changes on an
arched bridge. The ground track slope limit applies to the approaches.

Bridge rails save their native bridge ID with the railway. If the deck is
removed or replaced, remove and rebuild its rails before using that crossing
again. The current client package fits track meshes to the deck and uses the
server's vehicle elevation packets during crossings.

The native smoke tests construct track over all eight solid materials in an
isolated world, traverse flat, graded and arched decks in both directions,
run a three-car train over the wooden arch, and reload the railway graph
and a track item from their saved data. The headless client test checks the
rail mesh against the native deck profile. A live acceptance run still needs
a connected client to lay track from the bridge menu, board and drive in
both directions while a second client observes, and cross again after
restarting the server with a train parked on the deck.

## Configuration and saved state

The supplied `mods/wurmrail.properties` has these defaults:

```properties
builders=*
operators=*
maxCars=8
maxNodes=10000
maxSpeed=5.0
autoResumeScheduled=true
freightTransferKgPerSecond=100
steamRockDrillIntervalSeconds=30
steamYarderIntervalSeconds=30
waterFreightLitresPerSecond=100
steamWaterPumpLitresPerSecond=100
steamWoodEnergyPerKg=1
steamCharcoalEnergyPerKg=3
steamCoalEnergyPerKg=6
steamDrillEnergyPerAction=10
steamDrillWaterLitresPerAction=10
steamYarderEnergyPerAction=10
steamYarderWaterLitresPerAction=5
steamPumpEnergyPerLitre=0.02
steamFuelBufferEnergy=60
steamWaterBufferLitres=100
steamSupplyLinkMetres=16
```

`*` allows any player. Use comma-separated character names to restrict building
or shared operation. GMs with power 2+ retain access. Vehicle owners can operate
their own trains; normal Wurm permissions still control land, locks, boarding
and cargo. Train controls require riding or being within eight metres of the
locomotive. Restart after changing configuration.

`maxSpeed` caps both forward and reverse. Existing trains retain their chosen
limit; choose **Full** to use a higher server cap. `autoResumeScheduled` resumes
only scheduled services last left running. Explicit STOP remains authoritative.
`freightTransferKgPerSecond` is the default kg/s for newly configured freight
stops. Each stop's saved setting can override it.
`steamRockDrillIntervalSeconds` controls the interval for all Steam Rock Drills
(0.1–86,400 seconds; default 30). Append it to a preserved older configuration
to override the default. Restart the server after changing it.
`waterFreightLitresPerSecond` sets the default for newly configured stored-water
LOAD/UNLOAD stops (0.1–10,000 L/s). Each stop saves its own editable rate.
`steamWaterPumpLitresPerSecond` sets the global pump production rate in L/s.
The deprecated direct-fill setting is ignored. An older unloading-default key
is accepted for compatibility, but new configurations use the freight key.
`steamYarderIntervalSeconds` controls the shared Steam Yarder harvest interval
(0.1–86,400 seconds; default 30). It changes harvest opportunities, not tree
growth. Append it to a preserved configuration to override the default.
The steam supply keys set fuel energy per kg, costs per completed action or
litre actually pumped, buffer capacities and supply linking range (4�64m).
Keep each buffer at least as large as a Drill/Yarder action cost; an impossible
configuration pauses with `SUPPLY CONFIG` rather than producing unpaid output.
Older preserved configuration files use the listed defaults for missing keys.

Each selected world's database directory contains:

| File | Saved data |
| --- | --- |
| `wurmrail/network.properties` | Track graph, alignments, signal definitions, trains and routes |
| `wurmrail/services.properties` | Running scheduled-service intent |
| `wurmrail/passengers.properties` | Stations, personal destinations and recent-drop-off state |
| `wurmrail/freight.properties` | Freight names, storage associations, filters, operations and rates |
| `wurmrail/steamSupplies.properties` | Explicit fuel/water links, internal supply credits and conservative pending-transaction recovery |
| `wurmrail/drills.properties` | Drill owners/locations, linked hoppers, enabled faces, next face and running settings |
| `wurmrail/yarders.properties` | Yarder owners/locations, linked bunks, replant species, harvest age, next managed tile and running settings |
| `wurmrail/waterfill.properties` | Legacy inactive direct-fill definitions retained for safe upgrades |
| `wurmrail/pumps.properties` | Pump owners/locations, selected natural sources, linked tank IDs and running intent |
| `wurmrail/waterunload.properties` | Stored-water LOAD/UNLOAD stops, owners, tank IDs and transfer rates |

Checked `.bak` copies are retained after subsequent saves. Back up the entire
world and this folder together. Saves contain checksums: **do not hand-edit
them**. A failed rail-state load disables the railway and logs the affected path.
Invalid drill state disables drill operation independently of train services.
Invalid Yarder state disables forestry independently of train services.
Invalid pump state disables pumping independently of train services.
Preserve the files and diagnostic before restoring a matching backup.
Reservations rebuild safely after restart; live waiting queues rebuild from
player presence. Older supported rail-state formats migrate when loaded.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Mod does not load | Start the server through its modloader; inspect the first WurmRail error in the server log. |
| Invisible vehicles, missing rails or question-mark models | Update the client files, fully restart the game, and confirm `wurmrailmodels` and `wurmrail-models.jar` load in its log. |
| Client stopped loading mods after Steam verification | Steam can restore `client.jar`; rerun the client installer with `-InstallModLoader`. |
| Train waits at red | Read **Train controls → Status / Traffic** and **Railway signals → Status**; a green belongs only to the named train. Keep power on while waiting. |
| No services move after Clear traffic jams | Check Power. Recovery leaves stopped services off; **Start all scheduled** resumes the listed services. |
| Trains cannot clear each other | Check parked vehicles, complete-consist clearance, receiving-loop orientation, backup-lane length and approach signals. |
| A stop is absent from route setup | Ensure it is linked to connected rail on the same network; refresh the route window. |
| Boarding or cargo access fails | Stop the train and check Wurm vehicle access, seats, locks and capacity. |
| Drill will not mine | Read its status; check enabled available walls, native mining permissions, an owned unlocked hopper within 8 metres, and hopper capacity. |
| Yarder will not harvest | Read Steam Yarder controls; check Fuel Supply, Water Supply, mature trees inside its circle, surface placement, forestry/planting permissions, and an owned unlocked Log Bunk within 8 metres with free capacity. |
| Steam machine has NO FUEL / NO WATER | Check the selected same-owner supply links, unlocked storage, current range and available fuel/water. Leave it enabled for automatic delivery recovery. |
| Pump is waiting | Read Steam Water Pump controls; check Fuel Supply, its valid natural source within 8m, same-owner unlocked tank within 16m, compatible water and free capacity. Full tanks resume automatically after water is removed. |
| Water freight does not transfer | Check LOAD/UNLOAD operation, stored source water, same-owner unlocked Tank Cars/tank, native capacity and compatible fresh/salt water. |

When reporting a problem, include WurmRail server/client versions, the full
Status and Traffic text, signal reason, track layout and number of wagons.
Preserve a copy of the world's complete `wurmrail` folder for reproduction.

## Manual installation and Linux

Use the same stopped-server and backup procedure. Install the matching official
modloader for each side; server and client loaders are different. Their source
links and pinned archive hashes are listed in [THIRD-PARTY.md](THIRD-PARTY.md).

Copy the release files to these locations, keeping other mods and settings:

```text
WurmServerLauncher/
  mods/wurmrail.properties
  mods/wurmrail/wurmrail.jar

WurmLauncher/
  mods/wurmrailmodels.properties
  mods/wurmrailmodels/wurmrailmodels.jar
  packs/wurmrail-models.jar
```

Run the official client patcher when installing a client loader for the first
time. On Windows this is `patcher.bat`. On Linux, from `WurmLauncher`, use Java 8:

```sh
java -cp patcher.jar:javassist.jar org.gotti.wurmunlimited.patcher.PatchClientJar
```

Start clients through Steam. Start the Linux server from `WurmServerLauncher`
with `./modlauncher.sh` (make it executable if needed). Ensure it uses Java 8;
the upstream scripts use JVM options that newer Java releases can reject.

## Updating or disabling

Stop the server before replacing files; close each game before updating its
client files. Reuse the installers to retain configuration and back up replaced
files. Keep a complete matching world/state backup for rollback.

To disable WurmRail, stop the server and move `mods/wurmrail.properties` outside
`mods`, then restart. Keep the JAR, configuration and saved state. Disabling the
mod does not delete its items or undo world changes; restore a complete world
backup to reverse those changes.

## Source builds and integrations

The source workspace requires licensed server/client installations, a full
JDK 8 or newer, Python 3, the server loader v0.47 and client loader v0.15.
Extract the loaders into `tools/modloader` and `tools/clientloader`, or supply
their directories explicitly. Then run:

```powershell
.\build.ps1 -JavaHome 'D:\Tools\jdk' -ServerDir 'D:\Games\Wurm Unlimited\WurmServerLauncher' -ClientDir 'D:\Games\Wurm Unlimited\WurmLauncher'
```

`-JavaHome` can be omitted when a JDK is available through `JAVA_HOME` or `PATH`.
Other options include `-ModLoaderDir`, `-ClientModLoaderDir`, `-Python`, and
`-UseExistingAssets` for unchanged generated art. Regenerate art after changing
geometry or textures. Optional model previews require NumPy and Pillow; normal generation
uses Python's standard library.

The build compiles Java 8 bytecode, runs core simulations and native server/client
checks, validates generated assets, and creates archives and SHA-256 companion
files under `dist`. Failed checks stop packaging. Fixtures remain under `build`;
the build does not install into the game or start a multiplayer server.
`tools/smoke.ps1` and `tools/client-smoke.ps1` rerun native checks separately.
Native checks supplement a graphical multiplayer playtest; they do not open
a live game window.

To recheck the share archives and their checksum files, run
`python tools/verify_release.py 2.30.3` from the source workspace.

Integrations use absolute compass directions (N=0 through NW=7).
`RailNetwork.getJunctionAlignment`, `setJunctionAlignment`,
`cycleJunctionAlignment` and `junctionAlignments` expose the physical two-bit
pair independently of UI. Train-owned requests use
`requestJunctionAlignment` and `releaseJunctionAlignment`, with occupancy checks.
`RailNetwork.signals()` exposes definitions, route requests and immutable
reservation snapshots through `SignalSystem`. A signal's aspect alone is
never permission to move: validate train-owned authority. Presentation code
consumes snapshots and does not grant reservations.

For optional operator diagnostics, `/rail help`, `/rail list`,
`/rail status [TRAIN]`, `/rail inspect [NODE]` and `/rail signals [NODE]` show the
available commands and current state. Command identifiers are internal names;
normal menus accept friendly displayed train and station names.
