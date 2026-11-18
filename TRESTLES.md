# Railway trestle surveying

Activate a dioptra near a surface railway endpoint or a clear surface tile and
choose **Plan Railway Trestle**. Place a range pole on the ground at the other
end. Activate the dioptra and choose **Mark Trestle Destination** on the pole.
The same player can do both. To relocate the destination, put the pole on a new
tile and mark it again. Open **Review Trestle Survey** using the dioptra on a
nearby ground tile to return to the draft. Unconnected endpoints accept track
elevations in metres. Existing rail endpoints use the current track height
and direction.

The survey window shows the alignment length, gradient and approximate timber
bent heights. A valid preview also sends temporary light beams to the surveyor.
Close the survey window to see the beam bases at the two approach joins and at
selected bent tops. **Update preview** or **Review Trestle Survey** refreshes the
beams for 60 seconds. Building, cancelling or changing the survey clears the old
beams. The preview uses markers for the alignment and support locations.

The survey reports the first validation failure, including construction
permissions, blocked tiles and track below terrain. Drafts last until the server
restarts.

With GM power 2, choose **GM build straight trestle** while near either
approach. Use a compass-aligned span with at least one interior tile and
ground approaches. The deck profile runs between the inside edges of the
two approach tiles, and trains follow the terrain on the approach side of
each join. If the survey reports an obstruction on the span, use its tile,
distance and height difference to locate the ground above the rail. The completed span
uses custom rails, decking, stringers, and stacked timber bents. Install the
matching client update for these models. The build message includes the span
name.

## Removing a trestle

As a GM, activate a dioptra and right-click a surface tile in the registered
span or at its approach. Choose **Remove Railway Trestle** and confirm the span.
This works on empty ground after the physical deck and timber have been
destroyed. Removal clears the saved span and its remaining pieces so you can
survey and build over the same tiles again. Clear trains, signals, and stops
from the span first. You can also use `/rail removetrestle NAME`.

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

## Curve surveys

Use the active dioptra on a placed range pole and choose **Add Trestle
Intermediate Pole**. Mark poles in travel order, then mark the destination.
You can add poles after marking the destination. To replace a pole, use
**Remove last intermediate pole** and mark the updated positions in order.

The track centreline passes through every marked pole position in survey
order. It joins existing ground rails in their open direction and chooses a
compass heading at an untracked approach. The poles can sit off those headings.
The survey measures grade along the curve, checks terrain and obstructions
across the deck width, and previews selected poles and bent tops.
The preview also checks whether a train can move between each ground approach
and the deck. If an approach is too steep or blocked, the survey identifies its
tile and withholds construction until the connection is clear.

`trestleMinCurveRadiusTiles` defaults to 3 tiles (12 metres). Set a higher
value for broader curves. Existing configurations keep their settings.
Set `trestleMinCurveRadiusTiles=3` to use the default on an existing server.
The planner can adjust the heading through intermediate poles to spread a turn
over adjacent spans. It keeps every pole and the headings of the approaches
fixed. Place poles along the desired arc of a tight turn or hairpin. If the
radius is still too tight, the survey names the section to adjust. The total
span length and grade limits also apply.
With GM power 2, stand near either approach and choose **GM build curved
trestle**. The regular trestle removal action removes a curved span too.
Install the matching client and server packages to see and walk on the curved
deck.

## Section elevations and route map

Mark the destination to calculate the route. Blank pole fields fit their
heights to the sampled terrain across the deck. If those heights alone cannot
clear the terrain, the planner can also change the grade between poles. It
keeps both ground joins and manually entered heights fixed and checks every
grade against `trestleMaxGradientPercent`. At a sloped ground join, the deck
shoulders can sit in the bank over the first half tile. The track centreline
still has to clear the ground.
Each pole shows its tile, ground elevation, planned rail elevation, and the
grade from the previous point. Enter a height in metres to fix a pole at that
height, or clear the field to use automatic fitting again. **Update preview**
recalculates the route.

The north-up map shows the start, destination and numbered poles. Use
**Zoom in** and **Zoom out**, with a pole or approach selected to inspect
that part of the route. **Fit map** returns to the whole crossing. Map
controls keep the elevations entered in the window.

## In-game verification

In a disposable world, survey a 50-tile valley crossing from a straight rail
endpoint. Check that the approach faces the destination and that the support
heights increase toward the valley floor. Move the pole off the approach
heading, lower an endpoint until the route intersects terrain, and try a slope
above the configured limit. Each case should show a red error. Replace or
remove the destination, reopen the survey and cancel it. Repeat with a builder
on a deed where they lack construction permission.

To try a curved span, place poles along a broad turn, with the first pole off
the ground rail's compass heading. Build the survey at either
approach. Walk the deck and drive a coupled train across it in both
directions, including both ground joins. After restarting the server, inspect
the same span and verify that the deck and rails still meet at the joins.

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
New waypoint spans use format 8, which records their curve type and approach
headings. Spans with fitted or fixed elevations save the resulting height at each
pole. Spans with automatic grades between poles use format 9 to save those
additional elevations. Existing format 7 trestles retain their original lines
and circular arcs.
Previously built waypoint spans retain their original grades. Each locomotive
and wagon samples its position along the saved alignment. Curved deck pieces
use that same alignment to reconstruct their mesh and walking surface.

The world adapter applies the graph's absolute Z to native vehicles. An
isolated server test carries a locomotive, cart and log car across a lowered
terrain strip and back. A separate survey fixture checks instant construction,
absolute deck height, saved timber and removal. The server saves the finished
track and timber item IDs with the network. The client reuses four deck meshes
and six stackable bent meshes through native zone item visibility.

For a connected-client check, cross both approach seams in each direction with
coupled wagons. Rejoin with the consist parked on the span and inspect the Z
position of each vehicle and deck seam. Trestle construction uses the GM build
action. Material-based construction is a future milestone.
