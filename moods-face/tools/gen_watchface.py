#!/usr/bin/env python3
"""Generates the four bezel complication slots in res/raw/watchface.xml.

Geometry follows the stock Pixel "Active" face (ComplicationArc in the system watch face
app): each slot owns a 90 degree quadrant, and the label starts 15 degrees past the
quadrant's axis with the gauge running to 75 degrees past it. Short-text complications have
no gauge; their icon and text are centered in the quadrant as one unit.

Run from anywhere: python3 moods-face/tools/gen_watchface.py
"""
import math
import os

F = os.path.join(os.path.dirname(__file__), "..", "src", "main", "res", "raw", "watchface.xml")

R = 207                 # ring radius shared by gauges, text and icons
GAUGE = 14              # stock Pixel arc thickness
FONT = 18
ICON = 22
TEXT_UP_D = 414         # TextCircular diameters, measured on the watch to center
TEXT_DOWN_D = 422       # 20px text on the ring (the two directions sit differently)
CHAR_DEG = 3.3          # approximate advance of one system-font character on the ring, in degrees
ICON_DEG = round(math.degrees(ICON / R), 2)
GAP_DEG = 1.5           # icon-to-text gap
LABEL_START = 15        # label start, degrees past the quadrant axis
GAUGE_END = 75          # gauge end, degrees past the quadrant axis
TRACK = "#33FFFFFF"     # stock Pixel gauge track: white at 20% opacity
WHITE = "#FFFFFFFF"
BAND = "#40000000"      # 25% black behind each bezel complication
BAND_THICK = 22         # wraps the icon and text
BAND_PAD = 0            # the round caps cover the ends of the icon and text
SHADOW = '<Shadow color="#FF000000" offsetX="0" offsetY="4" radius="26">'
# Icon halo: (grow in px on each side, color) for each translucent copy, and its y offset.
ICON_HALO = ((6, "#14000000"), (5, "#1A000000"), (4, "#21000000"), (3, "#29000000"),
             (2, "#33000000"), (1, "#3D000000"))
ICON_HALO_DY = 2

TEXT_LEN = "textLength([COMPLICATION.TEXT])"
RANGED = ("clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / "
          "([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1)")
GOAL = "clamp([COMPLICATION.GOAL_PROGRESS_VALUE] / [COMPLICATION.GOAL_PROGRESS_TARGET], 0, 1)"

# name, slotId, default provider, default type, quadrant axis, gauge end (time clearance)
SLOTS = [
    ("Upper left", 11, "SUNRISE_SUNSET", "SHORT_TEXT", 270, 345),
    ("Upper right", 12, "DAY_AND_DATE", "SHORT_TEXT", 0, 75),
    ("Lower left", 13, "STEP_COUNT", "SHORT_TEXT", 180, 255),
    ("Lower right", 14, "WATCH_BATTERY", "RANGED_VALUE", 90, 155),
]

IND = "                "


def fmt(v):
    return f"{v:g}"


def masked_icon(x, y, size, angle, color):
    return f"""{IND}        <Group name="Icon" x="{x}" y="{y}" width="{size}" height="{size}" angle="{angle}">
{IND}            <PartImage x="0" y="0" width="{size}" height="{size}" renderMode="MASK">
{IND}                <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
{IND}            </PartImage>
{IND}            <PartDraw x="0" y="0" width="{size}" height="{size}" renderMode="SOURCE">
{IND}                <Rectangle x="0" y="0" width="{size}" height="{size}">
{IND}                    <Fill color="{color}" />
{IND}                </Rectangle>
{IND}            </PartDraw>
{IND}        </Group>
"""


def icon(angle_expr, bottom):
    """Icon on the ring at angle_expr, upright in the bottom half.

    The icon is drawn at 12 o'clock inside a full-face group that rotates about the face
    center, so its angle can follow the text length. It is a mask over solid fills so it
    renders white whatever color the provider sends, and the faint, slightly larger copies
    beneath stand in for the text's soft shadow, which PartImage can't take.
    """
    turn = 180 if bottom else 0
    x0, y0 = 225 - ICON // 2, 225 - R - ICON // 2

    def ring(dy, body):
        return f"""{IND}<Group name="Icon ring" x="0" y="{dy}" width="450" height="450">
{IND}    <Transform target="angle" value="{angle_expr}" />
{body}{IND}</Group>
"""
    shadow = "".join(masked_icon(x0 - g, y0 - g, ICON + 2 * g, turn, c)
                     for g, c in ICON_HALO)
    return ring(ICON_HALO_DY, shadow) + ring(0, masked_icon(x0, y0, ICON, turn, WHITE))


def text(start, end, bottom, align):
    d = TEXT_DOWN_D if bottom else TEXT_UP_D
    direction = "COUNTER_CLOCKWISE" if bottom else "CLOCKWISE"
    if bottom:
        start, end = end, start
    return f"""{IND}<PartText x="0" y="0" width="450" height="450">
{IND}    <TextCircular centerX="225" centerY="225" width="{d}" height="{d}"
{IND}        startAngle="{fmt(start)}" endAngle="{fmt(end)}" direction="{direction}" align="{align}" ellipsis="TRUE">
{IND}        <Font family="SYNC_TO_DEVICE" size="{FONT}" weight="BOLD" color="{WHITE}">
{IND}            {SHADOW}
{IND}                <Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template>
{IND}            </Shadow>
{IND}        </Font>
{IND}    </TextCircular>
{IND}</PartText>
"""


def arc(start, end, color, transforms="", thickness=GAUGE, radius=R):
    return f"""{IND}<PartDraw x="0" y="0" width="450" height="450">
{IND}    <Arc centerX="225" centerY="225" width="{2 * radius}" height="{2 * radius}" startAngle="{fmt(start)}" endAngle="{fmt(end)}">
{transforms}{IND}        <Stroke color="{color}" thickness="{thickness}" cap="ROUND" />
{IND}    </Arc>
{IND}</PartDraw>
"""


def band(start, end, transforms=""):
    return arc(start, end, BAND, transforms, BAND_THICK)


def gauge_layout(axis, end, frac):
    """Icon at the label start, text after it, gauge from the end of the text to `end`."""
    bottom = 90 <= axis < 270
    icon_at = axis + LABEL_START + ICON_DEG / 2
    text_at = axis + LABEL_START + ICON_DEG + GAP_DEG
    gauge_start = (f"clamp({fmt(text_at + 3)} + {TEXT_LEN} * {CHAR_DEG}, "
                   f"{fmt(text_at)}, {fmt(end - 12)})")
    track_t = f'{IND}        <Transform target="startAngle" value="{gauge_start}" />\n'
    fill_t = (track_t + f'{IND}        <Transform target="endAngle" '
              f'value="{gauge_start} + ({fmt(end)} - ({gauge_start})) * {frac}" />\n')
    return (band(axis + LABEL_START - BAND_PAD, end + BAND_PAD)
            + arc(end - 12, end, TRACK, track_t)
            + arc(end - 12, end - 12, WHITE, fill_t)
            + icon(fmt(icon_at), bottom)
            # Text runs clockwise away from the icon; in the bottom half that's its end.
            + text(text_at, text_at + 32, bottom, "END" if bottom else "START"))


def centered_layout(axis):
    """Icon and text centered together in the quadrant, no gauge."""
    bottom = 90 <= axis < 270
    mid = axis + 45
    icon_at = f"{fmt(mid - GAP_DEG / 2)} - {TEXT_LEN} * {CHAR_DEG / 2}"
    text_mid = mid + (ICON_DEG + GAP_DEG) / 2
    half = f"{TEXT_LEN} * {CHAR_DEG / 2} + {fmt((ICON_DEG + GAP_DEG) / 2 + BAND_PAD)}"
    band_t = (f'{IND}        <Transform target="startAngle" value="{fmt(mid)} - ({half})" />\n'
              f'{IND}        <Transform target="endAngle" value="{fmt(mid)} + {half}" />\n')
    return (band(mid, mid, band_t) + icon(icon_at, bottom)
            + text(text_mid - 28, text_mid + 28, bottom, "CENTER"))


def slot(name, sid, provider, ptype, axis, end):
    return f"""
        <ComplicationSlot slotId="{sid}" name="{name}" x="0" y="0" width="450" height="450"
            supportedTypes="RANGED_VALUE GOAL_PROGRESS SHORT_TEXT EMPTY"
            isCustomizable="TRUE">
            <DefaultProviderPolicy defaultSystemProvider="{provider}"
                defaultSystemProviderType="{ptype}" />
            <BoundingArc centerX="225" centerY="225" width="450" height="450" thickness="95"
                startAngle="{axis + 2}" endAngle="{axis + 88}" />
            <Complication type="RANGED_VALUE">
{gauge_layout(axis, end, RANGED)}            </Complication>
            <Complication type="GOAL_PROGRESS">
{gauge_layout(axis, end, GOAL)}            </Complication>
            <Complication type="SHORT_TEXT">
{centered_layout(axis)}            </Complication>
            <Complication type="EMPTY" />
        </ComplicationSlot>
"""


HEADER = """<!--
            Bezel complications, generated by moods-face/tools/gen_watchface.py (edit that, not
            this block). Laid out like the stock Pixel Active face: each slot owns a quadrant of
            the bezel ring (radius 207). Ranged and goal values show the icon and value 15 degrees
            past the quadrant's axis with a gauge running on to 75 degrees (155 on the lower right,
            to clear the time); short text shows the icon and value centered in the quadrant.
            Labels use the watch's own font (SYNC_TO_DEVICE), the same one as the stock faces,
            each on a thin 25% black band so it stays readable over any Mood.
            Each slot spans the whole face so everything is drawn in face coordinates; the
            BoundingArc is the tap/edit area and also clips drawing.
        -->
"""


def main():
    block = HEADER + "".join(slot(*s) for s in SLOTS)
    src = open(F).read()
    start = src.index("<!--\n            Bezel complications")
    end = src.index("<!--\n            Time, with")
    src = src[:start] + block.strip() + "\n\n        " + src[end:]
    open(F, "w").write(src)


if __name__ == "__main__":
    main()
