#!/usr/bin/env python3
"""
Authored geographic skeleton for MUMBAI: THE ISLAND CITY.

PROVENANCE / HONESTY NOTE
-------------------------
The build environment used to compile this world has no outbound access to OpenStreetMap,
Geofabrik, Overpass, SRTM/Copernicus or any other geodata host (the sandbox allowlist is
github.com, pypi.org and npmjs.org only).  Rather than fake an OSM import, every coordinate
in this file is *authored* from public geographic knowledge of Mumbai / Salsette: coastline
and creek traces, major road and rail corridors, airport runway geometry, lake outlines,
hill locations and district centroids.

Consequences, stated plainly (see docs/DATA_SOURCES.md):
  * Positions are correct to roughly +/- 0.3-2 km for coastline detail and +/- 100-300 m for
    the major corridors / runways.  They are NOT survey grade and are NOT OSM derived.
  * No OpenStreetMap geometry is redistributed, therefore no OSM attribution is claimed.
  * tools/osm_import.py is provided so the identical downstream pipeline can be re-run
    against a real OSM extract on a machine with network access; the world format,
    streaming, coverage tests and renderer do not change.

Coordinate convention: WGS84 decimal degrees, (lat, lon).
"""

# --------------------------------------------------------------------------------------
# Area of interest: physical Salsette / Mumbai island
# --------------------------------------------------------------------------------------

AOI_NAME = "Mumbai / Salsette island (Vasai Creek -> Colaba, Arabian Sea -> Thane Creek)"
AOI_VERSION = "1.0.0"

# Outer analysis bounds (includes the marine / airspace buffer).
AOI_BOUNDS = {
    "min_lat": 18.840,
    "max_lat": 19.430,
    "min_lon": 72.660,
    "max_lon": 73.080,
}

# Marine + airspace buffer around the island, metres.
WATER_BUFFER_M = 6000.0

# The physical island ring: west coast north -> south, then east coast south -> north.
ISLAND_OUTLINE = [
    # ---- West coast, north to south (Arabian Sea on the west) ----
    (19.3400, 72.7950),   # Vasai Creek mouth, Bhayandar west shore
    (19.3150, 72.7820),   # Uttan north
    (19.2950, 72.7760),   # Uttan / Dongri headland
    (19.2750, 72.7800),   # Gorai north beach
    (19.2550, 72.7830),   # Gorai beach
    (19.2420, 72.7880),   # Gorai Creek mouth, north bank
    (19.2330, 72.8010),   # Gorai Creek inland
    (19.2250, 72.8090),   # Gorai Creek head
    (19.2180, 72.7980),   # Gorai Creek south bank
    (19.2100, 72.7900),   # Manori
    (19.2000, 72.7880),   # Marve
    (19.1900, 72.7960),   # Malad (Marve) Creek mouth, north bank
    (19.1830, 72.8140),   # Malad Creek inland (Malvani)
    (19.1720, 72.8240),   # Malad Creek head
    (19.1650, 72.8090),   # Malad Creek south bank
    (19.1560, 72.7970),   # Madh Island north
    (19.1400, 72.7935),   # Madh / Erangal tip
    (19.1340, 72.8010),   # Versova Creek mouth, north bank
    (19.1310, 72.8160),   # Versova Creek inland
    (19.1375, 72.8270),   # Versova Creek head (Lokhandwala back bay)
    (19.1270, 72.8205),   # Versova Creek south bank
    (19.1300, 72.8075),   # Versova point
    (19.1200, 72.8115),   # Versova beach
    (19.1060, 72.8225),   # Juhu beach north
    (19.0900, 72.8262),   # Juhu beach
    (19.0760, 72.8255),   # Juhu Koliwada
    (19.0650, 72.8225),   # Santacruz west shore
    (19.0550, 72.8195),   # Khar Danda
    (19.0450, 72.8180),   # Bandra Bandstand
    (19.0375, 72.8195),   # Bandra Land's End
    (19.0300, 72.8255),   # Mahim Bay, north shore
    (19.0282, 72.8400),   # Mahim Creek mouth (Mithi river)
    (19.0355, 72.8560),   # Mithi river inland (Kurla / BKC edge)
    (19.0270, 72.8430),   # Mahim Creek south bank
    (19.0205, 72.8355),   # Mahim
    (19.0105, 72.8285),   # Dadar / Shivaji Park
    (19.0000, 72.8150),   # Worli Sea Face
    (18.9900, 72.8130),   # Worli point
    (18.9805, 72.8085),   # Haji Ali bay
    (18.9700, 72.8050),   # Breach Candy
    (18.9600, 72.7985),   # Malabar Point
    (18.9500, 72.7955),   # Walkeshwar
    (18.9480, 72.8085),   # Girgaon Chowpatty
    (18.9400, 72.8205),   # Marine Drive
    (18.9280, 72.8215),   # Nariman Point
    (18.9150, 72.8185),   # Cuffe Parade
    (18.9000, 72.8155),   # Colaba west
    (18.8930, 72.8175),   # Navy Nagar
    (18.8900, 72.8235),   # Colaba Point (southern tip)
    # ---- East coast, south to north (Mumbai Harbour / Thane Creek on the east) ----
    (18.9080, 72.8335),   # Sassoon Dock
    (18.9220, 72.8372),   # Gateway of India
    (18.9350, 72.8425),   # Ballard Estate
    (18.9500, 72.8455),   # Mazgaon docks
    (18.9650, 72.8510),   # Sewri west
    (18.9880, 72.8600),   # Sewri mudflats
    (19.0080, 72.8700),   # Wadala
    (19.0010, 72.8820),   # Mahul Creek mouth
    (18.9930, 72.9000),   # Mahul
    (18.9900, 72.9250),   # Trombay south shore
    (19.0050, 72.9400),   # Trombay east
    (19.0300, 72.9420),   # Mankhurd south
    (19.0520, 72.9330),   # Mankhurd / Vashi bridge approach
    (19.0750, 72.9400),   # Govandi east, Thane Creek western bank
    (19.1000, 72.9460),   # Deonar / Vikhroli creek edge
    (19.1200, 72.9520),   # Vikhroli east
    (19.1450, 72.9600),   # Bhandup pumping station / mangroves
    (19.1700, 72.9680),   # Mulund east
    (19.1900, 72.9750),   # Thane creek head (west bank)
    (19.2200, 72.9700),   # Kolshet / Ulhas confluence
    (19.2600, 72.9500),   # Ulhas river, south bank
    (19.2950, 72.9150),   # Ulhas / Vasai Creek, south bank
    (19.3200, 72.8750),   # Vasai Creek, south bank
    (19.3350, 72.8300),   # Vasai Creek, south bank west
    (19.3400, 72.7950),   # close the ring at the creek mouth
]

# Disconnected islands inside the AOI (rendered, not required playable land).
HARBOUR_ISLANDS = [
    ("Elephanta Island", [(18.9700, 72.9250), (18.9720, 72.9400), (18.9580, 72.9420),
                          (18.9540, 72.9280)]),
    ("Butcher Island", [(18.9480, 72.8950), (18.9520, 72.9040), (18.9430, 72.9060),
                        (18.9400, 72.8980)]),
    ("Cross Island", [(18.9330, 72.8700), (18.9350, 72.8740), (18.9300, 72.8750),
                      (18.9290, 72.8710)]),
]

# Inland water bodies (drinking water lakes are world barriers, not navigable).
LAKES = [
    ("Powai Lake",  [(19.1350, 72.8950), (19.1330, 72.9120), (19.1180, 72.9170),
                     (19.1150, 72.9020), (19.1240, 72.8930)], False),
    ("Vihar Lake",  [(19.1550, 72.9020), (19.1520, 72.9250), (19.1330, 72.9280),
                     (19.1320, 72.9080)], False),
    ("Tulsi Lake",  [(19.1800, 72.8930), (19.1790, 72.9100), (19.1650, 72.9120),
                     (19.1650, 72.8950)], False),
]

# Mangrove / marsh belts (tidal, distinct material + vegetation rules).
MANGROVE_BELTS = [
    [(19.0750, 72.9330), (19.1500, 72.9520), (19.1900, 72.9650), (19.1850, 72.9720),
     (19.1400, 72.9580), (19.0700, 72.9400)],                      # Thane Creek west bank
    [(19.1850, 72.8080), (19.1950, 72.7980), (19.2100, 72.7950), (19.2050, 72.8120)],
    [(19.0150, 72.8650), (19.0350, 72.8600), (19.0400, 72.8700), (19.0200, 72.8750)],
    [(19.2900, 72.8600), (19.3250, 72.8450), (19.3300, 72.8650), (19.2950, 72.8800)],
]

# Beaches along the Arabian Sea shore.
BEACHES = [
    ("Girgaon Chowpatty", 18.9548, 72.8120, 420),
    ("Dadar Chowpatty", 19.0260, 72.8330, 700),
    ("Juhu Beach", 19.0960, 72.8250, 5200),
    ("Versova Beach", 19.1310, 72.8120, 2400),
    ("Aksa Beach", 19.1760, 72.7950, 1400),
    ("Marve Beach", 19.1960, 72.7930, 900),
    ("Gorai Beach", 19.2550, 72.7850, 2600),
    ("Manori Beach", 19.2200, 72.7900, 1800),
]

# Hills: (name, lat, lon, peak height m, radius m).
HILLS = [
    ("Malabar Hill", 18.9550, 72.7980, 55, 1400),
    ("Cumbala Hill", 18.9680, 72.8080, 42, 900),
    ("Antop Hill", 19.0180, 72.8650, 38, 900),
    ("Pali Hill", 19.0680, 72.8290, 62, 900),
    ("Gilbert Hill", 19.1340, 72.8350, 61, 300),
    ("Trombay Hill", 19.0050, 72.9150, 302, 2600),
    ("Kanheri Ridge", 19.2080, 72.9060, 467, 4200),
    ("Tulsi Ridge", 19.1750, 72.9050, 410, 3000),
    ("Yeoor Hills", 19.2350, 72.9400, 380, 3400),
    ("Borivali Ridge", 19.2250, 72.8800, 320, 3000),
    ("Powai Hill", 19.1300, 72.9080, 180, 1600),
    ("Mahakali Hill", 19.1300, 72.8700, 90, 1100),
]

# Sanjay Gandhi National Park / Aarey forest polygon.
NATIONAL_PARK = [
    (19.2550, 72.8800), (19.2580, 72.9450), (19.2100, 72.9600), (19.1600, 72.9350),
    (19.1450, 72.9000), (19.1550, 72.8700), (19.1900, 72.8550), (19.2300, 72.8600),
]
AAREY_FOREST = [
    (19.1600, 72.8650), (19.1700, 72.8900), (19.1450, 72.8950), (19.1350, 72.8750),
]

# --------------------------------------------------------------------------------------
# Road corridors.  class: 0 motorway/expressway, 1 trunk arterial, 2 primary, 3 secondary
# lanes = per direction. layer: 0 ground, 1 flyover/bridge, -1 tunnel.
# --------------------------------------------------------------------------------------

ROADS = [
    ("Western Express Highway", 0, 4, 0, [
        (19.0430, 72.8400), (19.0560, 72.8420), (19.0720, 72.8450), (19.0880, 72.8520),
        (19.1000, 72.8540), (19.1120, 72.8560), (19.1250, 72.8550), (19.1420, 72.8520),
        (19.1650, 72.8490), (19.1830, 72.8470), (19.2050, 72.8540), (19.2290, 72.8580),
        (19.2500, 72.8600), (19.2700, 72.8630), (19.2900, 72.8680), (19.3100, 72.8600),
        (19.3300, 72.8450),
    ]),
    ("Eastern Express Highway", 0, 4, 0, [
        (19.0380, 72.8630), (19.0520, 72.8760), (19.0640, 72.8850), (19.0800, 72.9080),
        (19.0950, 72.9180), (19.1100, 72.9290), (19.1300, 72.9330), (19.1480, 72.9360),
        (19.1700, 72.9470), (19.1880, 72.9600),
    ]),
    ("Eastern Freeway", 0, 3, 1, [
        (18.9540, 72.8430), (18.9680, 72.8470), (18.9850, 72.8540), (19.0020, 72.8610),
        (19.0150, 72.8680), (19.0320, 72.8780), (19.0520, 72.8880), (19.0700, 72.8960),
    ]),
    ("Sion Panvel Highway", 0, 3, 0, [
        (19.0400, 72.8650), (19.0480, 72.8820), (19.0530, 72.9000), (19.0520, 72.9180),
        (19.0560, 72.9330), (19.0640, 72.9600),
    ]),
    ("Swami Vivekananda Road", 1, 2, 0, [
        (19.0340, 72.8420), (19.0540, 72.8370), (19.0760, 72.8370), (19.0950, 72.8400),
        (19.1180, 72.8440), (19.1400, 72.8450), (19.1650, 72.8440), (19.1880, 72.8430),
        (19.2100, 72.8490), (19.2300, 72.8540), (19.2480, 72.8560),
    ]),
    ("Link Road", 1, 3, 0, [
        (19.0560, 72.8300), (19.0760, 72.8290), (19.0980, 72.8300), (19.1180, 72.8310),
        (19.1400, 72.8330), (19.1620, 72.8360), (19.1850, 72.8370), (19.2080, 72.8430),
        (19.2300, 72.8480),
    ]),
    ("Lal Bahadur Shastri Marg", 1, 2, 0, [
        (19.0400, 72.8660), (19.0580, 72.8760), (19.0700, 72.8830), (19.0860, 72.9080),
        (19.1050, 72.9240), (19.1300, 72.9300), (19.1500, 72.9340), (19.1720, 72.9450),
    ]),
    ("Marine Drive", 2, 3, 0, [
        (18.9260, 72.8230), (18.9330, 72.8215), (18.9420, 72.8190), (18.9500, 72.8130),
        (18.9548, 72.8120),
    ]),
    ("Mumbai Coastal Road", 0, 4, 0, [
        (18.9420, 72.8180), (18.9550, 72.8070), (18.9680, 72.8000), (18.9800, 72.8020),
        (18.9920, 72.8080), (19.0000, 72.8130),
    ]),
    ("Bandra Worli Sea Link", 0, 4, 1, [
        (19.0020, 72.8155), (19.0100, 72.8150), (19.0200, 72.8160), (19.0300, 72.8170),
        (19.0390, 72.8185),
    ]),
    ("Jogeshwari Vikhroli Link Road", 1, 2, 0, [
        (19.1360, 72.8440), (19.1340, 72.8650), (19.1260, 72.8880), (19.1200, 72.9080),
        (19.1130, 72.9250), (19.1090, 72.9330),
    ]),
    ("Santacruz Chembur Link Road", 1, 2, 1, [
        (19.0810, 72.8470), (19.0790, 72.8650), (19.0740, 72.8800), (19.0680, 72.8900),
    ]),
    ("Andheri Kurla Road", 2, 2, 0, [
        (19.1180, 72.8480), (19.1060, 72.8630), (19.0960, 72.8740), (19.0820, 72.8800),
        (19.0700, 72.8830),
    ]),
    ("Linking Road", 2, 2, 0, [
        (19.0470, 72.8330), (19.0600, 72.8330), (19.0750, 72.8340), (19.0870, 72.8370),
    ]),
    ("Mahim Causeway", 1, 2, 1, [
        (19.0290, 72.8380), (19.0340, 72.8400), (19.0400, 72.8410),
    ]),
    ("Dr Annie Besant Road", 2, 2, 0, [
        (18.9900, 72.8180), (19.0000, 72.8250), (19.0080, 72.8320),
    ]),
    ("Dr B Ambedkar Road", 2, 2, 0, [
        (18.9500, 72.8350), (18.9700, 72.8350), (18.9900, 72.8380), (19.0080, 72.8450),
        (19.0250, 72.8540), (19.0390, 72.8600),
    ]),
    ("Peddar Road", 2, 2, 0, [
        (18.9620, 72.8060), (18.9700, 72.8110), (18.9760, 72.8170),
    ]),
    ("Haji Ali Seaface", 2, 2, 0, [
        (18.9780, 72.8120), (18.9860, 72.8160), (18.9950, 72.8200),
    ]),
    ("Juhu Tara Road", 2, 2, 0, [
        (19.0830, 72.8290), (19.0960, 72.8290), (19.1060, 72.8280),
    ]),
    ("Madh Marve Road", 3, 1, 0, [
        (19.1400, 72.7960), (19.1600, 72.8020), (19.1800, 72.8080), (19.1950, 72.7960),
    ]),
    ("Gorai Road", 3, 1, 0, [
        (19.2250, 72.7950), (19.2420, 72.7920), (19.2600, 72.7900), (19.2850, 72.7830),
    ]),
    ("Ghodbunder Road (Salsette section)", 1, 2, 0, [
        (19.2300, 72.8600), (19.2550, 72.8900), (19.2700, 72.9200), (19.2600, 72.9500),
    ]),
    ("Trombay Road", 2, 2, 0, [
        (19.0300, 72.9050), (19.0150, 72.9180), (19.0000, 72.9250),
    ]),
    ("Mumbai Trans Harbour Link (west approach)", 0, 3, 1, [
        (18.9950, 72.8650), (18.9900, 72.8900), (18.9850, 72.9200), (18.9800, 72.9500),
    ]),
    ("Palm Beach Approach (Vashi Bridge)", 0, 3, 1, [
        (19.0570, 72.9340), (19.0640, 72.9650),
    ]),
    ("Dahisar Mira Road", 1, 2, 0, [
        (19.2500, 72.8600), (19.2700, 72.8680), (19.2900, 72.8700), (19.3050, 72.8600),
    ]),
    ("Versova Link", 3, 1, 0, [
        (19.1300, 72.8130), (19.1290, 72.8300), (19.1310, 72.8430),
    ]),
    ("Powai Ring", 3, 1, 0, [
        (19.1150, 72.9040), (19.1250, 72.9100), (19.1350, 72.9050), (19.1300, 72.8930),
        (19.1180, 72.8950),
    ]),
    ("Airport Road", 2, 2, 0, [
        (19.0960, 72.8520), (19.0990, 72.8650), (19.0990, 72.8740), (19.0930, 72.8860),
    ]),
    ("Colaba Causeway", 2, 2, 0, [
        (18.9050, 72.8180), (18.9140, 72.8260), (18.9220, 72.8320), (18.9300, 72.8340),
    ]),
    ("P D'Mello Road", 2, 2, 0, [
        (18.9350, 72.8410), (18.9480, 72.8430), (18.9600, 72.8450),
    ]),
    ("Senapati Bapat Marg", 2, 2, 0, [
        (18.9780, 72.8280), (18.9900, 72.8290), (19.0020, 72.8330), (19.0150, 72.8400),
    ]),
    ("Mulund Airoli Link", 1, 2, 1, [
        (19.1650, 72.9550), (19.1600, 72.9800),
    ]),
]

# --------------------------------------------------------------------------------------
# Rail corridors.  kind: 0 suburban, 1 metro (elevated), 2 monorail.
# --------------------------------------------------------------------------------------

RAILWAYS = [
    ("Western Line", 0, 0, [
        (18.9350, 72.8270), (18.9450, 72.8240), (18.9550, 72.8210), (18.9620, 72.8160),
        (18.9710, 72.8200), (18.9790, 72.8260), (18.9900, 72.8330), (19.0000, 72.8400),
        (19.0190, 72.8430), (19.0340, 72.8420), (19.0540, 72.8400), (19.0700, 72.8400),
        (19.0870, 72.8410), (19.1050, 72.8440), (19.1200, 72.8470), (19.1380, 72.8480),
        (19.1550, 72.8480), (19.1750, 72.8490), (19.1900, 72.8500), (19.2080, 72.8540),
        (19.2290, 72.8570), (19.2500, 72.8600), (19.2810, 72.8680), (19.3010, 72.8510),
        (19.3300, 72.8460), (19.3500, 72.8450),
    ]),
    ("Central Line", 0, 0, [
        (18.9400, 72.8355), (18.9550, 72.8350), (18.9760, 72.8330), (18.9900, 72.8360),
        (19.0040, 72.8400), (19.0190, 72.8440), (19.0330, 72.8560), (19.0450, 72.8700),
        (19.0650, 72.8790), (19.0860, 72.9080), (19.1100, 72.9180), (19.1440, 72.9370),
        (19.1720, 72.9440), (19.1860, 72.9750),
    ]),
    ("Harbour Line", 0, 0, [
        (18.9400, 72.8355), (18.9550, 72.8420), (18.9700, 72.8470), (18.9950, 72.8620),
        (19.0180, 72.8630), (19.0330, 72.8700), (19.0450, 72.8820), (19.0480, 72.9290),
        (19.0620, 72.9550),
    ]),
    ("Harbour Line (Bandra branch)", 0, 0, [
        (19.0180, 72.8630), (19.0250, 72.8500), (19.0400, 72.8420), (19.0540, 72.8400),
    ]),
    ("Metro Line 1", 1, 1, [
        (19.1310, 72.8210), (19.1290, 72.8340), (19.1230, 72.8450), (19.1180, 72.8560),
        (19.1100, 72.8690), (19.1000, 72.8810), (19.0920, 72.8930), (19.0860, 72.9080),
    ]),
    ("Metro Line 2A/7", 1, 1, [
        (19.2500, 72.8600), (19.2290, 72.8540), (19.2050, 72.8460), (19.1830, 72.8400),
        (19.1600, 72.8390), (19.1400, 72.8420), (19.1200, 72.8480), (19.1100, 72.8530),
    ]),
    ("Mumbai Monorail", 2, 1, [
        (19.0620, 72.8990), (19.0480, 72.8880), (19.0330, 72.8700), (19.0180, 72.8630),
        (19.0000, 72.8450), (18.9800, 72.8280),
    ]),
]

# Named stations: (name, lat, lon, line index)
STATIONS = [
    ("Churchgate", 18.9350, 72.8270, 0), ("Marine Lines", 18.9450, 72.8240, 0),
    ("Charni Road", 18.9550, 72.8210, 0), ("Grant Road", 18.9620, 72.8160, 0),
    ("Mumbai Central", 18.9710, 72.8200, 0), ("Dadar West", 19.0190, 72.8430, 0),
    ("Bandra", 19.0540, 72.8400, 0), ("Andheri", 19.1200, 72.8470, 0),
    ("Goregaon", 19.1650, 72.8490, 0), ("Borivali", 19.2290, 72.8570, 0),
    ("Dahisar", 19.2500, 72.8600, 0), ("Mira Road", 19.2810, 72.8680, 0),
    ("Bhayandar", 19.3010, 72.8510, 0),
    ("CSMT", 18.9400, 72.8355, 1), ("Byculla", 18.9760, 72.8330, 1),
    ("Dadar", 19.0190, 72.8440, 1), ("Kurla", 19.0650, 72.8790, 1),
    ("Ghatkopar", 19.0860, 72.9080, 1), ("Bhandup", 19.1440, 72.9370, 1),
    ("Mulund", 19.1720, 72.9440, 1),
    ("Sewri", 18.9950, 72.8620, 2), ("Wadala Road", 19.0180, 72.8630, 2),
    ("Mankhurd", 19.0480, 72.9290, 2),
    ("Versova Metro", 19.1310, 72.8210, 4), ("Ghatkopar Metro", 19.0860, 72.9080, 4),
]

# --------------------------------------------------------------------------------------
# Airports: runways are modelled at true length / heading at 1:1 scale.
# (icao, name, centre lat, centre lon, elevation m, [(designator, true heading deg,
#  length m, width m, centre lat, centre lon)])
# --------------------------------------------------------------------------------------

AIRPORTS = [
    ("VABB", "Chhatrapati Shivaji Maharaj International", 19.0887, 72.8679, 11.0, [
        ("09/27", 89.0, 3445.0, 60.0, 19.0890, 72.8679),
        ("14/32", 139.0, 2871.0, 45.0, 19.0935, 72.8640),
    ]),
    ("VAJJ", "Juhu Aerodrome", 19.0989, 72.8347, 4.0, [
        ("08/26", 82.0, 1143.0, 30.0, 19.0985, 72.8345),
        ("16/34", 162.0, 1179.0, 30.0, 19.0992, 72.8352),
    ]),
]

# Helipads / open operating areas used for helicopter gameplay (fictional operations).
HELIPADS = [
    ("Juhu Apron Pad", 19.0975, 72.8320, 6.0),
    ("Bandra Kurla Pad", 19.0660, 72.8690, 12.0),
    ("Mahalaxmi Pad", 18.9830, 72.8190, 8.0),
    ("Powai Pad", 19.1230, 72.9080, 20.0),
    ("Borivali North Pad", 19.2320, 72.8620, 14.0),
]

# Public waterfront launch points for the boat.
BOAT_LAUNCHES = [
    ("Gateway of India Jetty", 18.9215, 72.8352, "harbour"),
    ("Sassoon Dock", 18.9095, 72.8330, "harbour"),
    ("Versova Fishing Jetty", 19.1320, 72.8090, "sea"),
    ("Madh Jetty", 19.1430, 72.7975, "sea"),
    ("Marve Jetty", 19.1965, 72.7925, "creek"),
    ("Gorai Jetty", 19.2350, 72.7960, "creek"),
    ("Mahul Jetty", 18.9960, 72.8870, "harbour"),
    ("Vikhroli Creek Jetty", 19.1150, 72.9480, "creek"),
    ("Sewri Jetty", 18.9960, 72.8630, "harbour"),
]

# --------------------------------------------------------------------------------------
# Districts: HUD location naming + urban density class.
# density: 0 forest/open, 1 low, 2 suburban, 3 dense urban, 4 very dense, 5 CBD towers
# (name, lat, lon, radius m, density)
# --------------------------------------------------------------------------------------

DISTRICTS = [
    ("Navy Nagar", 18.8960, 72.8160, 1200, 2),
    ("Colaba", 18.9080, 72.8180, 1600, 3),
    ("Cuffe Parade", 18.9140, 72.8220, 1100, 4),
    ("Nariman Point", 18.9260, 72.8230, 900, 5),
    ("Fort", 18.9340, 72.8340, 1200, 4),
    ("Churchgate", 18.9330, 72.8270, 800, 4),
    ("Marine Drive", 18.9430, 72.8200, 1100, 4),
    ("Girgaon", 18.9550, 72.8160, 1100, 4),
    ("Malabar Hill", 18.9540, 72.7990, 1300, 3),
    ("Breach Candy", 18.9680, 72.8060, 900, 3),
    ("Tardeo", 18.9690, 72.8120, 900, 4),
    ("Mumbai Central", 18.9710, 72.8200, 1000, 4),
    ("Byculla", 18.9780, 72.8330, 1300, 4),
    ("Mazgaon", 18.9640, 72.8460, 1200, 3),
    ("Mahalaxmi", 18.9820, 72.8180, 1100, 4),
    ("Worli", 19.0000, 72.8180, 1600, 4),
    ("Lower Parel", 18.9950, 72.8300, 1300, 5),
    ("Parel", 19.0030, 72.8400, 1200, 4),
    ("Sewri", 18.9950, 72.8580, 1400, 3),
    ("Prabhadevi", 19.0150, 72.8290, 900, 4),
    ("Dadar", 19.0190, 72.8440, 1500, 4),
    ("Shivaji Park", 19.0280, 72.8370, 900, 3),
    ("Wadala", 19.0180, 72.8650, 1600, 3),
    ("Matunga", 19.0280, 72.8500, 1100, 4),
    ("Sion", 19.0390, 72.8630, 1300, 3),
    ("Mahim", 19.0330, 72.8410, 1200, 4),
    ("Dharavi", 19.0420, 72.8530, 1500, 4),
    ("Bandra West", 19.0550, 72.8290, 1700, 3),
    ("Bandra East", 19.0620, 72.8480, 1400, 3),
    ("Bandra Kurla Complex", 19.0670, 72.8690, 1500, 5),
    ("Kurla", 19.0700, 72.8800, 1800, 3),
    ("Chembur", 19.0530, 72.8990, 2000, 3),
    ("Mankhurd", 19.0490, 72.9280, 1800, 2),
    ("Trombay", 19.0020, 72.9170, 2600, 1),
    ("Mahul", 18.9960, 72.8900, 1600, 1),
    ("Ghatkopar", 19.0860, 72.9080, 1900, 3),
    ("Vidyavihar", 19.0800, 72.8970, 1100, 3),
    ("Santacruz", 19.0820, 72.8410, 1600, 3),
    ("Khar", 19.0700, 72.8370, 1100, 3),
    ("Vile Parle", 19.0990, 72.8440, 1400, 3),
    ("Juhu", 19.0940, 72.8270, 1500, 2),
    ("Andheri West", 19.1250, 72.8350, 2100, 3),
    ("Andheri East", 19.1150, 72.8650, 2200, 3),
    ("Versova", 19.1320, 72.8150, 1300, 2),
    ("Jogeshwari", 19.1380, 72.8480, 1600, 3),
    ("Powai", 19.1230, 72.9060, 1900, 3),
    ("Vikhroli", 19.1110, 72.9300, 1700, 3),
    ("Bhandup", 19.1450, 72.9360, 1800, 3),
    ("Mulund", 19.1720, 72.9460, 2000, 3),
    ("Goregaon", 19.1650, 72.8500, 2200, 3),
    ("Film City", 19.1670, 72.8790, 1800, 0),
    ("Aarey Colony", 19.1520, 72.8790, 2200, 0),
    ("Malad", 19.1870, 72.8480, 2200, 3),
    ("Marve", 19.1930, 72.7960, 1300, 1),
    ("Madh Island", 19.1480, 72.7980, 1800, 1),
    ("Kandivali", 19.2060, 72.8520, 2200, 3),
    ("Charkop", 19.2130, 72.8300, 1400, 2),
    ("Borivali", 19.2300, 72.8570, 2200, 3),
    ("Gorai", 19.2420, 72.7930, 2000, 1),
    ("Sanjay Gandhi National Park", 19.2100, 72.9100, 5200, 0),
    ("Dahisar", 19.2520, 72.8620, 1800, 3),
    ("Mira Road", 19.2820, 72.8690, 2400, 3),
    ("Bhayandar", 19.3030, 72.8520, 2200, 3),
    ("Uttan", 19.2980, 72.7820, 2000, 1),
    ("Vasai Creek South Bank", 19.3300, 72.8300, 3000, 1),
    ("Thane Creek West Bank", 19.1800, 72.9650, 2600, 0),
    ("Kanjurmarg", 19.1300, 72.9350, 1500, 3),
    ("Nahur", 19.1570, 72.9430, 1100, 2),
    ("Deonar", 19.0620, 72.9180, 1600, 2),
    ("Govandi", 19.0560, 72.9100, 1400, 3),
    ("Anushakti Nagar", 19.0380, 72.9220, 1400, 1),
]

# --------------------------------------------------------------------------------------
# Hero landmarks: original geometry is generated for these by the renderer.
# kind: see world/LandmarkKind in the Java core.
# (name, lat, lon, heading deg, kind, param_a, param_b)
# --------------------------------------------------------------------------------------

LANDMARKS = [
    ("Gateway of India", 18.9220, 72.8347, 90.0, "arch", 26.0, 48.0),
    ("Taj Palace", 18.9217, 72.8332, 90.0, "heritage_block", 70.0, 24.0),
    ("Chhatrapati Shivaji Terminus", 18.9398, 72.8355, 0.0, "heritage_station", 150.0, 56.0),
    ("Bombay High Court", 18.9300, 72.8295, 0.0, "heritage_block", 120.0, 32.0),
    ("Rajabai Clock Tower", 18.9296, 72.8305, 0.0, "tower_clock", 18.0, 85.0),
    ("Flora Fountain", 18.9322, 72.8318, 0.0, "plaza", 40.0, 8.0),
    ("Wankhede Stadium", 18.9389, 72.8258, 0.0, "stadium", 180.0, 42.0),
    ("Marine Drive Promenade", 18.9430, 72.8190, 0.0, "promenade", 3400.0, 14.0),
    ("Haji Ali Dargah", 18.9827, 72.8090, 0.0, "dome_shrine", 40.0, 26.0),
    ("Mahalaxmi Racecourse", 18.9820, 72.8170, 0.0, "open_green", 700.0, 4.0),
    ("Siddhivinayak Temple", 19.0170, 72.8302, 0.0, "temple", 30.0, 34.0),
    ("Antilia", 18.9680, 72.8095, 0.0, "tower", 32.0, 173.0),
    ("Imperial Towers", 18.9820, 72.8230, 0.0, "twin_tower", 38.0, 254.0),
    ("World One Tower", 18.9950, 72.8230, 0.0, "tower", 44.0, 280.0),
    ("Worli Sea Face Towers", 19.0030, 72.8150, 0.0, "tower_cluster", 300.0, 150.0),
    ("Bandra Worli Sea Link", 19.0200, 72.8160, 0.0, "cable_bridge", 5600.0, 126.0),
    ("Bandra Fort", 19.0420, 72.8190, 0.0, "fort", 60.0, 14.0),
    ("Mahim Fort", 19.0305, 72.8365, 0.0, "fort", 48.0, 10.0),
    ("Worli Fort", 19.0000, 72.8135, 0.0, "fort", 44.0, 12.0),
    ("Dharavi Quarter", 19.0420, 72.8530, 0.0, "informal_quarter", 1200.0, 9.0),
    ("Bandra Kurla Complex", 19.0665, 72.8690, 0.0, "cbd_cluster", 900.0, 120.0),
    ("Nariman Point CBD", 18.9265, 72.8232, 0.0, "cbd_cluster", 600.0, 110.0),
    ("Lower Parel Mills", 18.9960, 72.8300, 0.0, "cbd_cluster", 700.0, 160.0),
    ("CSMIA Terminal 2", 19.0990, 72.8740, 139.0, "terminal", 320.0, 32.0),
    ("Juhu Aerodrome Apron", 19.0975, 72.8330, 82.0, "apron", 180.0, 10.0),
    ("Global Vipassana Pagoda", 19.2320, 72.8020, 0.0, "dome_shrine", 60.0, 96.0),
    ("Kanheri Caves Ridge", 19.2080, 72.9060, 0.0, "rock_ridge", 400.0, 60.0),
    ("Powai Hiranandani", 19.1215, 72.9080, 0.0, "tower_cluster", 400.0, 90.0),
    ("Film City Backlot", 19.1670, 72.8790, 0.0, "open_green", 600.0, 12.0),
    ("Mumbai Port Docks", 18.9520, 72.8460, 0.0, "port", 900.0, 28.0),
    ("Sewri Mudflats", 18.9930, 72.8640, 0.0, "mudflat", 1200.0, 2.0),
    ("Trombay Industrial", 19.0080, 72.9120, 0.0, "industrial", 800.0, 70.0),
    ("Elephanta Caves", 18.9633, 72.9315, 0.0, "rock_ridge", 200.0, 50.0),
    ("Chowpatty Beach", 18.9548, 72.8120, 0.0, "promenade", 500.0, 10.0),
    ("Juhu Beach Promenade", 19.0960, 72.8250, 0.0, "promenade", 2000.0, 10.0),
    ("Versova Koliwada", 19.1320, 72.8110, 0.0, "informal_quarter", 400.0, 7.0),
    ("Borivali Station Market", 19.2290, 72.8570, 0.0, "market", 260.0, 12.0),
    ("Crawford Market", 18.9470, 72.8347, 0.0, "market", 200.0, 16.0),
    ("Dadar Flower Market", 19.0190, 72.8430, 0.0, "market", 180.0, 12.0),
    ("Vasai Creek Bridge", 19.3290, 72.8460, 0.0, "rail_bridge", 1400.0, 18.0),
    ("Thane Creek Bridge", 19.0600, 72.9500, 0.0, "road_bridge", 1800.0, 22.0),
    ("Mumbai Trans Harbour Link", 18.9900, 72.9000, 0.0, "cable_bridge", 7000.0, 40.0),
]

# Player spawn: a recognisable, walkable public location with a car nearby.
DEFAULT_SPAWN = ("Marine Drive", 18.9420, 72.8205, 180.0)

# --------------------------------------------------------------------------------------
# Mandatory geographic checkpoints (section 2 of the master prompt).
# Every group must have packaged, populated chunks or the build fails.
# --------------------------------------------------------------------------------------

CHECKPOINTS = [
    ("vasai_creek_north", "Vasai Creek northern edge", 19.3300, 72.8400),
    ("northern_salsette", "Northern Salsette / Mira-Bhayandar side", 19.2900, 72.8600),
    ("dahisar_borivali_belt", "Dahisar / Borivali / Kandivali / Malad / Goregaon belt", 19.2200, 72.8550),
    ("sgnp_lakes", "Sanjay Gandhi National Park and lake system", 19.1700, 72.9050),
    ("thane_creek_west", "Thane Creek western shoreline and mangroves", 19.1500, 72.9550),
    ("eastern_suburbs", "Powai / Vikhroli / Ghatkopar / Chembur / Trombay", 19.0700, 72.9150),
    ("airports", "CSMIA and Juhu aerodrome", 19.0940, 72.8520),
    ("andheri_juhu_versova", "Andheri / Juhu / Versova", 19.1200, 72.8300),
    ("bandra_bkc_dharavi", "Bandra / Kurla / BKC / Dharavi / Mahim", 19.0550, 72.8520),
    ("central_mumbai", "Bandra-Worli and central Mumbai", 19.0000, 72.8250),
    ("south_mumbai", "South Mumbai, Fort, Marine Drive, docks", 18.9350, 72.8300),
    ("colaba_navy_nagar", "Colaba and Navy Nagar shoreline", 18.9000, 72.8180),
    ("arabian_sea_west", "Arabian Sea west of Madh / Versova", 19.1400, 72.7700),
    ("mumbai_harbour", "Mumbai Harbour water", 18.9500, 72.8900),
]


def all_polylines():
    """Every authored polyline, for reporting."""
    out = []
    for name, cls, lanes, layer, pts in ROADS:
        out.append(("road", name, pts))
    for name, kind, layer, pts in RAILWAYS:
        out.append(("rail", name, pts))
    return out


if __name__ == "__main__":
    print(f"AOI: {AOI_NAME} v{AOI_VERSION}")
    print(f"island outline points : {len(ISLAND_OUTLINE)}")
    print(f"roads                 : {len(ROADS)}")
    print(f"railways              : {len(RAILWAYS)}")
    print(f"districts             : {len(DISTRICTS)}")
    print(f"landmarks             : {len(LANDMARKS)}")
    print(f"checkpoints           : {len(CHECKPOINTS)}")
