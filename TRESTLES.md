# Railway trestle surveying

Activate a dioptra near a surface railway endpoint or a clear surface tile and
choose **Plan Railway Trestle**. Place a range pole on the ground at the other
end. Activate the dioptra and choose **Mark Trestle Destination** on the pole.
The same player can do both. To relocate the destination, put the pole on a new
tile and mark it again. Open **Review Trestle Survey** using the dioptra on a
nearby ground tile to return to the draft. Unconnected endpoints accept track
elevations in metres. Existing rail endpoints use the current track height
and direction.

The survey window previews a straight alignment, its gradient and approximate
timber bent heights. It highlights the first validation failure, including
construction permissions, blocked tiles and track below the terrain. Drafts
last until the server restarts. The preview is a survey window.

With GM power 2, choose **GM build straight trestle** while near either
approach. Use a compass-aligned span with at least one interior tile and
ground approaches. The deck profile runs between the inside edges of the
two approach tiles, and trains follow the terrain on the approach side of
each join. If the survey reports an obstruction on the span, use its tile,
distance and height difference to locate the ground above the rail. The completed span
uses custom rails, decking, stringers, and stacked timber bents. Install the
matching client update for these models. The build message includes the span
name. Use `/rail inspect NODE` to inspect a section and `/rail removetrestle NAME`
to remove the span after clearing trains, signals, and stops.

With the walkable-trestle server and client updates installed, players on
foot can cross the existing timber deck in either direction. Use the ground
approaches at either end and stay on the planks. Walking beside or below the
deck still uses the terrain.

Set `trestleMaxTiles` (default 250, up to 500), `trestleMaxGradientPercent`
(default 50), and `trestleBentSpacingTiles` (default 3) in
`mods/wurmrail.properties`. The grade is the rise in metres divided by the
horizontal distance in metres. Each dirt is 0.1 m, so 20 dirt over one 4 m
cardinal tile is a 50% grade. A diagonal tile step has a longer horizontal
run. The configured limit applies when previewing and building a new span.
Trains follow its saved elevation profile. For an existing installation
with `trestleMaxGradientPercent=5`, change it to `50` and restart the server
to use the new default.

## In-game verification

In a disposable world, survey a 50-tile valley crossing from a straight rail
endpoint. Check that the approach faces the destination and that the support
heights increase toward the valley floor. Move the pole off the approach
heading, lower an endpoint until the route intersects terrain, and try a slope
above the configured limit. Each case should show a red error. Replace or
remove the destination, reopen the survey and cancel it. Repeat with a builder
on a deed where they lack construction permission.

## Persistent elevated track

WurmRail's existing graph assigns one node to each occupied tile and routes
trains using adjacency between nodes. Its position sampler evaluates a
quadratic Bezier for each traversed tile, and the world adapter currently
resolves Z from terrain or a native solid bridge deck. The client already
receives explicit Z movement updates and uses terrain or the native deck for
rail mesh deformation and vehicle tilt.

The core can now associate a straight height profile with linked surface rails.
Each coupled car samples its own absolute Z in either direction. Networks with
new trestle approach transitions use save format 6 and the existing checksum
and backup logic. Older elevated spans retain their saved profiles.
Networks without elevated edges continue to save in format 4.

The world adapter applies the graph's absolute Z to native vehicles. An
isolated server test carries a locomotive, cart and log car across a lowered
terrain strip and back. A separate survey fixture checks instant construction,
absolute deck height, saved timber and removal. The server saves the finished
track and timber item IDs with the network. The client reuses four deck meshes
and six stackable bent meshes through native zone item visibility.

For a connected-client check, cross both approach seams in each direction with
coupled wagons. Rejoin with the consist parked on the span and inspect the Z
position of each vehicle and deck seam. Curved geometry, player-funded
construction, and live multiplayer tests remain future milestones.
