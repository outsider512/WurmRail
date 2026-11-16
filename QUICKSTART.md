# WurmRail 2.31.1 — quick start

WurmRail needs a **server installation** and a **client installation on every
player's PC**. Download the server and client ZIPs from the release page. See
[README.md](README.md) for terrain, permissions, advanced layouts and recovery.

Update both the server and each player's client for curved deck models, walking
surfaces and the zoomable survey map. Blank intermediate pole elevations fit
automatically to the terrain. See [TRESTLES.md](TRESTLES.md) for surveying and
construction.

## Install

1. Stop the server and back up its complete world/database, including
   `wurmrail`. Close every player's game and launcher.
2. Extract the release outside the game folders.
3. On the server computer, open PowerShell in the extracted folder and run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\Install-WurmRail.ps1 -ServerDir 'D:\Games\Wurm Unlimited\WurmServerLauncher'
   ```

4. On **each player's PC**, run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\Install-WurmRailClient.ps1 -ClientDir 'D:\SteamLibrary\steamapps\common\Wurm Unlimited\WurmLauncher'
   ```

Replace the paths with your own. Add `-InstallModLoader` to the appropriate
command if its installation has no working modloader. Server and client loaders
are different. The installers retain configuration and back up replaced files.

Start the server through `modlauncher.bat`; launch clients through Steam.
Install both updates for bridge rail support. Fully close and restart each
player's client.

## Make a short railway

1. Prepare clear, dry ground. Straight rail allows slope 20 along the track and
   zero across it. Bends and junctions must be flat.
2. Craft **rail track sections** under construction materials, beginning with
   a plank and an iron band. Carry the finished sections.
3. Right-click ground → **Build railway** → choose direction and length →
   **Build track**. Each tile uses one section.
4. Craft a **rail locomotive** under **Carts**. Complete it on connected rail,
   or open **Train controls → Place on track**. Registration is automatic.
5. Craft a wagon behind it and choose **Couple rail wagon**. Stop and disembark
   before changing couplings. Leave enough track for the whole train.
6. Choose **Embark Passenger** or **Board**. Open **Train controls** for
   **Power on**, **STOP**, speed presets and **Reverse manually**.

Each vehicle seats four riders. **Full** uses the server speed cap, normally
5 m/s in either direction. Reversing backs the train; it does not spin around,
and choosing **Reverse manually** clears its automatic route. Use normal Wurm
disembarking and cargo **Open / Load / Unload** actions.

On a finished solid bridge, right-click the deck for **Build railway**.
Connect the rails at a ground-level bridge exit where the approach meets the
deck. Rails use the bridge's grade. Rope bridges reject track.

## Add stations and a repeating route

1. Right-click clear ground directly beside a rail → **Build station**.
   Select its rail and enter a name. Build another connected station.
2. Stop the locomotive → **Train controls → Set up train route**.
3. Add the stops in travel order → **Save and start**. The service repeats.

Stand on a station tile to wait; the Links badge shows your queue state. Choose
a destination in **Station**, or **My destination** while aboard. Leaving the
tile or boarding clears waiting. A dropped-off player must leave the tile
before waiting at the same station again. These states are per player.

## Pump water into storage, then transport it

Craft **industrial water tank** under **Containers**. It stores **100,000 litres**
and needs **4.5 metres of headroom**. Craft **steam water pump** under **Tools**;
place it on the ground with **2.2 metres of headroom**.

1. Put the pump within **8 metres** of a valid natural water source, and an owned,
   unlocked Industrial Water Tank within **16 metres** on the same ground level.
2. Open **Steam Water Pump controls**, select the source, destination tank and
   **Fuel Supply** storage, then choose
   **Start pumping**. The default is **100 L/s**. Full tanks pause production and
   automatically resume when capacity becomes available. **Stop pumping** is a
   persistent manual stop.
3. Craft **rail tank car** under **Carts** and couple it. Its new capacity is
   **5,000 litres / 5,000 kg of water**; solid cargo is rejected.
4. Put the source storage tank within **8 metres** of a dedicated rail. Use
   **Water freight stop → LOAD from tank**, select its tank and name it.
5. At the destination, configure **Water freight stop → UNLOAD into tank** with
   a second Industrial Water Tank within **8 metres** of that rail.
6. Add the LOAD and UNLOAD names to the normal train route and start it.

Stops, tanks, train and cars share an owner. Transfers use existing stored water
at **100 L/s** by default, shared across the train. A 5,000-litre car takes about
**50 seconds** to fill; STOP pauses transfer. Empty sources, full destinations or
incompatible water end the visit cleanly. Native **Open** and pouring provide
manual access. Keep incompatible fresh/salt water separate.

Old direct Water Fill Stations are inactive legacy waypoints. Replace them in
routes with stored-water LOAD stops. Existing Water Unload Stops retain their
names and tank links as UNLOAD freight stops. Existing overfull Tank Cars keep
all their water and can unload; new filling obeys the 5,000-litre limit.

Pumps require fuel and their natural water source, with no separate boiler-water
tank. Rock Drills and Yarders require fuel plus a Water Supply Industrial Water
Tank. Locomotives still do not consume fuel or water. No fire, pressure or
heating/startup simulation is added.

## Supply steam machines

Use the Fuel Supply and Water Supply selectors in each machine's controls.
Supply storage must share the owner, remain unlocked/unsealed and be within
**16 metres** on the same ground level. Keep the existing output link too.
Pumps have Fuel Supply only and use their natural source for boiler water.

Use Coal/charcoal in a Bulk Hopper, or logs/felled trees/kindling/wood scraps
in compatible storage. Fuel and output can share one hopper. Coal is preferred
and gives six times wood's default energy per kilogram; charcoal gives three.
For bootstrap, use wood to run the Pump and Drill, then select the Drill's own
Coal Hopper as Fuel Supply after its first production.

Default costs: Drill **10 energy + 10 L per action**; Yarder **10 energy + 5 L
per tree**; Pump **0.02 energy per litre pumped**. Buffers hold **60 energy /
100 L**. All values and range are configurable. No useful work means no work
consumption. Leave machines enabled to resume after deliveries; press Stop
only for a persistent manual stop. See [steam supplies](README.md#steam-machine-supplies).

## Steam Rock Drills

Craft **steam rock drill** under **Tools**, starting with a plank and iron band,
and complete its sheets, planks, bands and nails on an open, dry mine floor.
Place an owned, unlocked Bulk Hopper within **8 metres** on the same cave level.

Right-click the drill → **Select drill Bulk Hopper**. Choose the hopper, enable
the available North/East/South/West faces, and choose **Start mining**. Each
30-second action creates one real resource in the hopper and advances to the
next enabled available face. **Steam rock drill status** explains any stop;
**Stop drill mining** stops it directly.

Each completed action uses fuel energy and water. Mining uses finite native
veins and stops before opening new tunnel tiles. A full or unavailable hopper,
missing fuel or missing water pauses an enabled drill; deliveries allow it to
resume automatically. Unexpected
output failures retain the real item in the drill; clear the hopper and Start
to retry delivery. Running settings survive restart with a fresh interval.
See [README.md](README.md#steam-rock-drills) for permissions and quality rules.

## Run freight

Place unlocked, unsealed storage beside two dedicated rail stops, within 8 metres
on the same level. Right-click each rail → **Freight stop**. Name one **Mine
Loader**, choose its storage, **LOAD**, **Any cargo**, and **100 kg/s**. Configure
the other as **Foundry Unloader** with **UNLOAD** and its destination storage.

Use an unlocked compatible wagon and the same owner for stops, storage, train and
wagon. Add the two freight names to the normal train route and **Save and start**.
The train waits while whole real items transfer over time, then continues when
the source is empty or the destination cannot accept more. Heavy items wait for
enough allowance. STOP pauses freight. Passenger stops and junction points need
separate rail tiles. See [README.md](README.md#automated-freight) for restrictions.
Each general wagon allows 10,000 kg, 100 stacks and 5,904 litres; the first limit
applies. Train controls shows current cargo capacity. Existing wagons update
without rebuilding them.

## Bulk Hoppers

For industrial storage, craft **bulk hopper** under **Containers**, starting
with a plank and iron band. Complete it on the ground and link it as freight
storage. It accepts approved ores and loose raw materials, up to 100,000 kg,
64,000 litres and 4,096 stacks. **Bulk hopper status** shows contents and free
capacity. Keep it unlocked for automatic freight.

## Manage a woodlot

Craft **log bunk** under **Containers**, beginning with a plank and iron band.
It accepts only logs and felled trees, up to **100,000 kg**, **64,000 litres**
and **4,096 stacks**. **Log bunk status** shows its contents and free capacity.
It can also be selected as ordinary freight-stop storage.

Craft **steam yarder** under **Tools**, beginning with a plank and iron band.
Finish it on dry surface ground outside buildings/bridges with **3.3 metres
of headroom**. Place an owned, unlocked and unsealed Log Bunk within **8 metres**.
Unlock the Yarder, then open **Steam Yarder controls**. Choose the bunk, replant
species and **Mature / Old / Very old / Overaged**. Also select **Fuel Supply**
and **Water Supply**, then **Start forestry**.

One eligible tree in the **five-tile circle** is harvested every **30 seconds**
and replanted with the selected species. Establish the first trees normally;
bare ground is not automatically planted. Trees grow only through Wurm's
normal simulation. Protected, obstructed and railway tiles are skipped;
settlement cutting and planting permissions apply. Fuel and water are used only
when a tree is actually harvested/replanted. Waiting for young trees is idle;
no sprouts are consumed.

Full or unavailable storage or missing supplies pause enabled forestry and
resume automatically when resolved. Confirmed timber stays in the
Yarder's safety compartment if delivery fails; clear storage and Start to retry.
An uncertain harvest requires operator inspection. **Stop forestry** stops it
directly. Settings and running intent persist, with a fresh interval on restart.
See [README.md](README.md#steam-yarders-and-log-bunks) for limits and recovery.

## Transport timber with a Log Car

Craft **log car** under **Carts**, beginning with a plank and iron band, then
finish its axles, sheets, bands, planks and nails. Use **Couple rail wagon** to
attach it. It accepts only **logs and felled trees**, up to **20,000 kg** or
**24,000 litres**, with actual native weight and volume deciding capacity.

Put the Yarder's Log Bunk within **8 metres** of a dedicated rail. Configure
**Freight stop → LOAD → Any cargo** with that Bunk. At the destination, configure
another rail as **UNLOAD**, linked to a second Log Bunk or compatible storage.
Add **Timber Loader → Timber Unloader** to the usual train route and start it.
Keep the train, cars, stops and storage under the same owner and unlocked.

Both timber forms enter the Log Car, including small logs. With a Log Car
attached, a full or inaccessible Log Car leaves timber in source storage;
it does not redirect timber into a general wagon. Loading uses the existing
**100 kg/s** default, so whole heavy items take longer. Full destination storage
leaves timber aboard and allows the route to continue. No Log Loader or sawmill
is needed. See [README.md](README.md#log-cars) for details.

## Share track

On a track or signal, open **Railway signals** and place a signal
for every used junction approach. The approach names the side a train arrives
**from**; opposite travel needs its own signal. Place protection beyond the
junction too, and leave space for complete trains to clear.

**RED means stop. GREEN belongs only to its named train.** Keep power on while
waiting. Automatic service aligns and locks the needed bidirectional junction
pair. Manual switching uses **Cycle junction alignment** or **Railway track**.

Opposing trains need turning or passing space. Use generous loops and holding
track for your first shared route. A single track cannot let trains pass
through one another.

## If something stops

Read **Train controls → Status / Traffic** and the signal's **Status** reason.
**Railway services → Clear traffic jams** coordinates running schedules;
operator-stopped trains remain stopped. **Start all scheduled** resumes the
listed services. Running schedules normally resume after a server restart.

Missing models usually require updating the client files and fully restarting
the game. For more help, see [README.md → Troubleshooting](README.md#troubleshooting).
