# WurmRail original model pack

Run `python tools/generate_models.py` from a fresh checkout to create the model pack, manifest and generated meshes.

These meshes were newly authored for WurmRail using `tools/generate_models.py`.
They contain no copied Wurm model geometry, materials, textures or animations.
Original opaque PNG color swatches preserve the palette in Wurm's texture
renderer; every triangle has valid nondegenerate UV coordinates.
The installed client's supported Collada importer and ground-item renderer
were inspected to verify the format, native coordinate basis, material names,
mount joint conventions and 16-bit draw limits.

Run `python tools/generate_models.py --previews` to regenerate the assets.
The generator uses only the Python standard library. Rendering the optional
preview sheets additionally needs Numpy and Pillow. Run
`python tools/validate_assets.py` to verify the packaged output independently.
The release build also tests the pack with the actual installed Wurm client
resource and Collada parsers through `tools/ClientModelsSmoke.java`.

`wurmrail-models.jar` is a self-contained client resource pack containing
818 Collada 1.4.1 models and `mappings.txt`. `serverpack/` retains editable native
asset sources. `manifest.json` records every key, bounding box, triangle count,
seat mount and SHA-256 hash in the emitted native coordinate basis.
`authored_mounts` records the original geometry locations and `server_mounts`
separately records Wurm's differently oriented Seat API offsets. `previews/`
includes inspectable GLB/OBJ vehicle
meshes and rendered preview sheets of the actual generated geometry.

The locomotive has a dark green boiler and open cab, brass fittings, spoked
wheels, coupling buffers, four seat mounts, controls and coal detail. The open
timber goods wagon has four passenger seats, metal straps, couplers and a hand
brake. Rails use steel head/web/base profiles, individual timber sleepers and
fixing plates. Terminal tiles have buffer stops; junctions add a switch lever;
stations add a platform and station sign. Scenery occupies a corner with no
diagonal connection. It is omitted on masks with all four diagonals connected,
where there is no clear corner. Terminal buffers sit beyond the stopping
vehicle's body in the unconnected half of the terminal tile.

The switch lever has no decorative white board or disconnected signpost.
The separate low alignment overlay still shows the active bidirectional path.
Client support conforms placed rail meshes to the track's terrain profile while
preserving horizontal endpoints and gauge. The authored models stay flat and
shared source geometry remains unchanged; each sloping instance owns its
deformed vertex and normal buffers. This also supports legal diagonal profiles
whose gradient changes within a tile.

Two separate passenger pads use the same static Y-up export: a green pickup
tile with an up-arrow sign and a red drop-off tile with a down-arrow sign.
They occupy the ground tile beside the rails and contain no rail geometry.
The passenger pads introduced in 2.2.0 preserved all 770 earlier model entries,
including the lowered locomotive rider mounts.

Version 2.3.0 adds 29 separate junction alignment indicators and preserves all
772 existing model entries. Each of the 28 unordered pairs of compass sides
has a bright yellow path with outward arrows at both connected ends. An
unaligned junction shows a red cross. These indicators sit above the railhead
without rotating or replacing the underlying connectivity mesh, so automatic
switching and manual cycling show the same absolute, bidirectional alignment.

Version 2.4.0 adds 16 directional signal models and preserves all 801 earlier
model entries. Each absolute approach has a red and green aspect. An upright
post stands beside the incoming rail, with an upper red lens/cross and lower
green lens/arrow; the inactive lens and reverse face remain dark. The approach
heading and post offset are baked into the static Y-up geometry, so neither
player heading nor native item rotation changes which direction is protected.
Bright original diffuse swatches and emission colors distinguish the aspects.

Geometry is authored in metres: +X east, -Y north / vehicle front, +Z up.
Native COLLADA exports bake tracks as `(x,y,z) -> (x,z,y)` and vehicles as
`(x,y,z) -> (-x,z,-y)` in every position, normal and mount translation, and
reverse triangle winding to preserve outward faces. The Wurm renderer uses
+Y up and +Z south; its importer does not apply
COLLADA's `up_axis` metadata. Emitted files declare `Y_UP` and contain native
Y-up coordinates directly. Merely declaring `Z_UP` leaves rails standing
upright. Static tracks use the ground-item render path with zero yaw.
Vehicles use the separate moving-creature render path with client yaw
`180 - serverHeading`, so their exported geometry and joints include a half
turn around native Y. Their native front is +Z before the moving-item yaw.

Tiles are four metres wide, placed at tile centres. Mask bits 0 through 7 represent N, NE, E,
SE, S, SW, W and NW. Every pair of exits is represented by a quadratic Bezier
with endpoints `2*A` and `2*B` and control point `(0,0)`, matching train movement.
There are four straight pairs and 24 bends. The 256 connectivity masks are
available in ordinary track, junction and station variants. The railhead is
0.21 metres above terrain. Wurm's static importer supplies the original rigid
meshes; the WurmRail client shapes each placed straight track instance along
its terrain centreline while preserving its horizontal tile footprint. Bends
and junctions still require flat railbeds under the construction rules.

Vehicle joints match the server seats after converting each frame correctly.
Authored locations are: locomotive X ±0.48, Y 0.65 and 1.25, Z 0.55; wagon
X ±0.55, Y ±0.70, Z 0.95. The native joint translation stores these as (-X,Z,-Y),
so locomotive joints have native Y 0.55 and wagon joints Y 0.95. Since 2.1.1,
the locomotive's character roots sit 0.50 m below their previous height to clear
the cab roof; these are root offsets, not cushion heights. Wurm's server
Seat API stores the same authored point as `(Y,-X,Z)`; its player movement
transform uses a different local frame from the model renderer. Both use `NullMount1` through
`NullMount4`. Vehicle model properties explicitly enable dynamic model data,
so the client can attach mounted players to those joints.

Do not enable `canHaveMountItems`: that property selects horse/boat equipment
meshes, and its native `MountItems` update hides every mesh with our railway
names. Passenger attachment uses the `NullMount` joints independently. The
client regression exercises the native vehicle-load callback, equipment update,
and normal/selection draw filters so a successfully imported but hidden vehicle
cannot pass the release checks.

Preview sheets are offline renders of the shipped model geometry. They are
not screenshots of a running Wurm client.
