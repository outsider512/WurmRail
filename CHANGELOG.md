# WurmRail release notes

## 2.31.0

- Survey custom timber trestles with a dioptra and range pole. The survey
  reports where the deck intersects terrain, and GMs can build or remove
  straight spans.
- Configure the maximum grade with `trestleMaxGradientPercent`. The default
  is 50%, or 20 dirt over a 4-metre cardinal tile.
- Trains and players on foot follow the sloped deck between the ground
  approaches. The server restores the saved grade on restart.

## 2.30.3

- Restore **Build railway** on elevated bridge decks and **Railway track**
  on existing bridge rails. The builder and train controls use the player's
  bridge ID when checking the level.
- Clarify that the selected tile counts toward the requested line length
  and that finished solid bridge decks use their own grade.

## 2.30.2

- Build railway track on completed solid Wurm bridges and connect through
  ground-level exits. Rope and incomplete bridges reject track.
- Track and coupled vehicles follow native deck profiles, including arched
  bridges. The graph and native items save the bridge ID.
- Client track meshes follow the deck slope. Moving vehicles retain explicit
  height from the server during bridge crossings.

## 2.30.1

- Fixed client-only coupling drift caused by per-car game-tick clocks advancing
  playback past the shared render timestamp.
- Existing-car visibility/metadata refreshes retain movement history instead of
  resetting just one car to its current server position.
- Native local-controller placeholder coordinates and smooth prediction
  corrections no longer overwrite authoritative visual history.
- Added native regressions for unmounted forward/reverse spacing, slow staggered
  ticks, real metadata refreshes, control changes, dismount and real teleports.
- Server gameplay, coupling, movement, routing, inventories and model assets are
  unchanged. No world migration is required.

Install the client update on every player's PC and fully restart the clients.
The existing 2.30.0 server remains compatible.

## 2.30.0

- Steam Rock Drills and Yarders now require linked fuel and water supplies;
  Water Pumps require fuel and use their natural source for boiler water.
- Added shared configurable fuel-energy values, explicit supply links, modest
  saved buffers and conservative work reservations for future steam equipment.
- Consumption follows completed actions or actual pumped volume. Idle Yarders,
  unavailable work and blocked outputs do not incur continual idle costs.
- Enabled machines wait for supplies/output capacity and resume automatically;
  manual Stop persists. Coal output and Fuel Supply can share one hopper.
- Added supply selectors/status and startup instructions. Locomotives remain
  independent of fuel/water; detailed boilers, pressure and maintenance are absent.

Update the server and matching client files, then configure each machine's
new supply links. Existing output, face, woodlot and source settings remain.

## 2.29.0

- Added distinct Coal Vein support without replacing a vanilla resource type.
- Added physical mineral Coal, separate from unchanged vanilla charcoal,
  using native item inventories and installed game artwork references.
- Added Coal to Steam Rock Drill mining, Bulk Hopper storage and existing
  general-wagon freight, including exact Coal filtering and finite depletion.
- Retained actual item identities and metadata throughout storage and transport.
  No new coal car, fuel consumption or map-distribution generation is included.

Install matching server and client updates before using a map containing Coal
Veins. `COAL-MAP-HANDOFF.md` gives exact placement bytes and finite counts.

## 2.28.0

- Increased Steam Yarder woodlots to a five-tile circular radius. Existing
  settings, selected species and harvest ages migrate without accelerating growth.
- Reduced Tank Car capacity to 5,000 litres / 5,000 kg. Existing overfull liquid
  is retained and can drain normally; no water is truncated on upgrade.
- Added the placeable Steam Water Pump, gradual configurable natural-water
  production into linked Industrial Water Tanks, saved controls and automatic
  full-tank pause/resume. Fuel, fire and steam requirements remain disabled.
- Replaced direct natural-water railway filling with stored-water LOAD/UNLOAD
  freight stops using existing route holds and conservative liquid transfer.
  Existing unloading definitions migrate; old direct-fill waypoints are inactive
  and safe for existing routes.

Update the server and every client, then restart for the new pump artwork.
Existing tracks, vehicles and storage remain compatible.

## 2.27.0

- Added the craftable Log Car with original stake-wagon artwork and the existing
  consist, movement, reversing, passenger, ownership and persistence systems.
- Log Cars accept only real logs and felled trees, sharing a reusable timber
  whitelist with Log Bunks. Native inventory and mutation guards enforce a
  20,000 kg / 24,000 litre payload using actual timber weight and volume.
- Existing freight stops load Log Bunks into Log Cars and unload them into
  compatible storage at their configured weight-based rate. Both timber forms
  select Log Cars in mixed consists; a full or unavailable Log Car leaves timber
  in source storage. Older consists without a Log Car retain previous behavior.
- Reused normal route holds, whole-item transfer, STOP/restart and completion
  handling. No separate timber routes, Log Loader, sawmill or new fuel requirement.

Update the server and every client, then restart. Previous models and existing
railway operation remain compatible; the client adds the Log Car model and its
native vehicle support.

## 2.26.0

- Added the craftable Steam Yarder with original winch/mast artwork, a bounded
  three-tile circular woodlot, replant species and harvest-age controls.
- Eligible trees yield real native logs or felled trees and are replanted with
  a young sprout. Normal Wurm growth, tree material/age output and forestry
  permissions are retained. Protected, railway and unsuitable tiles are skipped.
- Added timber-only Log Bunks with original artwork, guarded native inventories,
  100,000 kg / 64,000 litre / 4,096-stack limits, status and freight-stop storage.
- Forestry runs at a configurable 30-second interval with saved settings,
  bounded scheduled work and a real-item safety compartment for failed delivery.
  No fuel, water, pressure, maintenance or sprouts are consumed yet.

Install the server and every client's update, then restart. Existing railway,
vehicle and industrial models are byte-identical; the pack adds two forestry
models. Log Cars, Log Loaders and sawmills remain future additions.

## 2.25.0

- Added the placeable Industrial Water Tank with original artwork, native
  100,000-litre storage, ordinary ownership and a quantity/capacity status window.
- Added routeable Water Unload Stops. Tank Cars unload real water into a linked
  tank over time using the existing arrival hold and a shared configurable L/s budget.
- Generalized liquid-container guards for separate mobile/static capacities and
  added conservative native transfer, rollback and exact-draw APIs.
- Added optional 16-metre consumer links with live permission, range and water
  checks. No existing drill or locomotive consumes water; no steam/fuel systems
  are enabled.

Update server and client packages, then restart. Existing routes and liquid items
remain compatible. New water-tank artwork needs 4.5 metres of vertical clearance.

## 2.24.0

- Added the craftable liquid-only Tank Car with original artwork, four end-platform
  seats and existing train coupling/movement/ownership support. Capacity is
  10,000 litres; water is initially supported as one native variable-weight item.
- Added named Water Fill Stations to normal train routes. They draw from a
  selected natural source within eight metres and fill compatible tanks over
  time, initially at a shared 100 L/s, then release the existing route hold.
- Added separately checksummed water-stop definitions, captured source selection,
  STOP/restart behavior, source validation and native liquid capacity/mixing guards.
- Existing freight and liquid services keep independent ownership of arrival
  holds. No junction, pathfinding or reservation algorithms were changed.

Install server and client updates, then restart. Automatic unloading, other liquids,
industrial water tanks and steam-machine water consumption are not included yet.

## 2.23.0

- Added the placeable Steam Rock Drill with original artwork, a normal crafting
  recipe, direct Start/Stop actions and captured hopper/face configuration.
- Selected adjacent N/E/S/W walls are mined in round-robin order at a configurable
  30-second interval. Real Wurm resources use native depletion and quality rules;
  depleted faces are skipped and tunnel advancement remains manual.
- Added reusable owned machine-to-Bulk-Hopper links, capacity preflight and a
  real-item safety compartment for unexpected output failures. No fuel, water,
  pressure, maintenance or other production machines are included yet.
- Drill settings, running intent and next face use separate checksummed state.
  Restart earns a fresh mining interval; no offline production backlog is created.

Install the server update and every client's updated model pack, then restart.
Existing railway routing, signals, reservations, vehicles and hopper limits remain.

## 2.22.1

- Raised cargo-wagon payload from 300 kg to 10,000 kg. The old weight cap caused
  departure after fifteen standard 20 kg shards. Native 100-stack and volume
  limits remain. Existing wagon items receive the new template limits.
- Added cargo capacity to Train controls and clearer freight completion details.
- Corrected the loaded passenger-seat camera transform to follow the vehicle's
  current interpolated heading, pitch and roll. Seat transforms now agree with
  the visible wagon on grades and turns, including disembarking and reboarding.

Update server and client files, then fully restart Wurm. Hopper artwork and
existing tracks, routes, inventories and hopper limits remain unchanged.

## 2.22.0

- Added a craftable ground Bulk Hopper with original artwork, a central raw-resource
  whitelist, guarded real-item storage and a contents/capacity inspection window.
- Hopper limits are 100,000 kg, 64,000 litres and 4,096 stacks. Freight stops can
  LOAD/UNLOAD hoppers using the same ownership and storage rules as ordinary stops.
- Client railway vehicles use one consistent playback horizon so wagons preserve
  their spacing during reverse travel when their recent packet histories differ.

Update the server and every client's files/model pack, then fully restart clients.
Train movement remains authoritative; client playback uses a shared 250 ms delay.

## 2.21.0

- Added arrival-triggered freight stops to the existing train route editor:
  named LOAD/UNLOAD locations reference nearby storage and serve attached wagons.
- Transfers move whole real items at a configurable kg/s rate, initially 100,
  with any-cargo or exact-template filtering, capacity checks and bounded work.
- Added checksummed freight definitions, durable pending arrivals, STOP/restart
  handling, and inventory recovery guards. Dedicated freight car types are not
  included yet; current cargo wagons act as general test cars.

Install the server update. Existing 2.20.1 clients and model packs remain
compatible. Freight uses unlocked owner-matched ground storage and wagons;
bulk, liquid and restricted cargo require future specialized loaders.

## 2.20.1

- Fixed straight rails inheriting another piece's slope or becoming too steep
  and disconnected as nearby track models load. Terrain shaping now waits
  until the ground item owns its private model copy.
- Added regression coverage for Wurm's real ground-item constructor followed
  by initialization, including an already-loaded shared model.

Update every player's client and fully restart the game. Existing 2.20.0
servers and the 2.20.0 model pack remain compatible. No tracks need rebuilding;
the misplaced geometry existed only in client memory.

## 2.20.0

- Removed the decorative floating white board and its disconnected post above
  junction switches. The red mechanical lever, low alignment indicator and
  existing switch interactions remain.
- Track meshes follow the terrain beneath them, including grade transitions
  and valid nonplanar diagonal ramps, so neighboring rails meet more naturally.
  The visual adjustment does not change track connections or train routing.
- Added a focused installation guide, quick start, package overview and
  troubleshooting reference for sharing the release.

Install the matching **2.20.0 server and client files** for the updated visuals,
including clients previously on 2.13.1 or newer. Back up the complete world and
its `wurmrail` folder before updating. Existing tracks, item identities,
routes and traffic behavior are retained.

## Recent compatibility milestones

- **2.19.0:** Station branch queues normally wait at the junction signal instead
  of taking extra laps. Clearance circuits remain available when another train
  or a backing return needs room.
- **2.18.0:** Opposed trains in Y loops preserve their outbound recovery intent;
  waiting entrants use a compatible loop arm. Approach holding points leave
  room for a long train's rear wagon to clear the points.
- **2.17.0:** Pull-in stations can use the nearest suitable junction and straight
  backup lane for local turnbacks. Registered surface rail prevents natural
  tree and bush growth.
- **2.16.0:** Coordinated Y-loop turnbacks, station branch queue fairness and
  short following blocks on simple loops.
- **2.15.1–2.15.2:** Prepared rail beds allow slope 20 along straight rails,
  zero side-to-side slope, and diagonal ramps with matching side corners.
  Bends and junctions require flat tiles.
- **2.13.1:** Smooth rider movement and terrain-following vehicle support.
- **2.10.x:** Restartable scheduled services, fleet recovery and improved
  surface-to-cave transitions.
- **2.7.x–2.8.0:** Combined passenger stations, equal forward/reverse limits,
  forward-preferring routes and coordinated same-direction connector traffic.
- **2.3.0–2.4.0:** Bidirectional physical junction alignments and protected
  train-owned signal routes; rail-state format 4.

Older supported saves migrate on load. Reverting a mod version may require
restoring the complete matching world and rail-state backup. Never downgrade
only the JAR while relying on a newer save format.
