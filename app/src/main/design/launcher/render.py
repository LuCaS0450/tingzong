"""Render the approved SVG as Android adaptive layers. Requires resvg-py."""

from copy import deepcopy
from pathlib import Path
from xml.etree import ElementTree

from resvg_py import svg_to_bytes


SVG_NS = 'http://www.w3.org/2000/svg'
ElementTree.register_namespace('', SVG_NS)
SOURCE = Path(__file__).with_name('tingzong-pin-note.svg')
OUTPUT = SOURCE.parents[2] / 'res' / 'drawable-xxxhdpi'
LAYER_PIXELS = 432  # 108 dp at xxxhdpi.


def find_by_id(root, tag, name):
    return root.find(f'{{{SVG_NS}}}{tag}[@id="{name}"]')


def write_layer(source, layer, padding, canvas):
    root = deepcopy(source)
    omitted = 'glyph' if layer == 'background' else 'background'
    root.remove(find_by_id(root, 'g', omitted))
    root.set('viewBox', f'{-padding:g} {-padding:g} {canvas:g} {canvas:g}')
    root.set('width', str(LAYER_PIXELS))
    root.set('height', str(LAYER_PIXELS))

    if layer == 'background':
        # Extend the original gradient across Android's outer 18 dp on each side.
        extent = dict(x=f'{-padding:g}', y=f'{-padding:g}', width=f'{canvas:g}', height=f'{canvas:g}')
        for rect in find_by_id(root, 'g', 'background'):
            rect.attrib.update(extent)
        for mask in root.findall(f'{{{SVG_NS}}}defs/{{{SVG_NS}}}mask'):
            if mask.get('id', '').startswith('red-mask-'):
                mask.attrib.update(extent)
                mask.find(f'{{{SVG_NS}}}rect').attrib.update(extent)

    image = svg_to_bytes(svg_string=ElementTree.tostring(root, encoding='unicode'))
    (OUTPUT / f'launcher_{layer}.png').write_bytes(image)


def main():
    source = ElementTree.parse(SOURCE).getroot()
    x, y, width, height = map(float, source.attrib['viewBox'].split())
    if (x, y) != (0, 0) or width != height:
        raise ValueError('Expected a square SVG viewBox starting at (0, 0)')

    # The original square occupies the 72 dp visible area of each 108 dp layer.
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for layer in ('background', 'foreground'):
        write_layer(source, layer, width / 4, width * 1.5)


if __name__ == '__main__':
    main()
