#!/usr/bin/env python3
"""Gera os ativos da marca do Valky a partir de uma fonte única.

A marca (escudo com asas e gema) é definida aqui em coordenadas e sai como:
  - SVG   -> landing, painel e README;
  - PNG   -> favicon, apple-touch-icon, splash e imagem de compartilhamento;
  - vetor -> ícone adaptativo do Android, monocromático, notificação, marcador
             do mapa e símbolo usado dentro do app.

Uso (precisa de rsvg-convert):

    python3 scripts/brand-assets.py

Cores da identidade: principal #806DF8, secundária #594DFC.
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN, SECOND = '#806DF8', '#594DFC'
GEM_LIGHT, GEM_DARK, CREASE = '#B7C7FC', '#6E8CF6', '#3B2FA8'
DARK = '#14121C'

# --- geometria da marca (mesmas proporções do desenho aprovado) --------------
W, H, C = 455.0, 335.0, 227.5
BBOX = (26.0, 18.0, 429.0, 310.0)  # viewBox justo: sem margem sobrando


def mirror(pts):
    return [(round(2 * C - x, 1), y) for x, y in reversed(pts)]


def d(pts):
    return 'M ' + ' L '.join(f'{x:g},{y:g}' for x, y in pts) + ' Z'


LEFT = [(26, 19), (33, 38), (55, 111), (83, 160), (168, 237), (220, 302), (228, 310), (190, 170), (164, 66)]
RIGHT = mirror(LEFT)
CREASE_LEFT = [(164, 66), (190, 170), (228, 310), (210, 300), (172, 168), (150, 70)]
CREASE_RIGHT = mirror(CREASE_LEFT)
GEM = [(C, 18), (C + 20, 100), (C, 190), (C - 20, 100)]
GEM_SHINE = [(C, 18), (C - 20, 100), (C, 190)]

VX, VY = int(BBOX[0]), int(BBOX[1])
VW, VH = int(BBOX[2] - BBOX[0]), int(BBOX[3] - BBOX[1])

DEFS = (f'<defs><linearGradient id="v" x1="0" y1="1" x2="1" y2="0">'
        f'<stop offset="0" stop-color="{SECOND}"/><stop offset="1" stop-color="{MAIN}"/></linearGradient>'
        f'<linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{GEM_LIGHT}"/><stop offset="1" stop-color="{GEM_DARK}"/></linearGradient></defs>')

SVG = (
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{VX} {VY} {VW} {VH}" width="{VW}" height="{VH}">\n'
    f'{DEFS}\n'
    f'<path d="{d(LEFT)}" fill="url(#v)"/>\n'
    f'<path d="{d(RIGHT)}" fill="url(#v)"/>\n'
    f'<path d="{d(CREASE_LEFT)}" fill="{CREASE}" opacity="0.35"/>\n'
    f'<path d="{d(CREASE_RIGHT)}" fill="{CREASE}" opacity="0.35"/>\n'
    f'<path d="{d(GEM)}" fill="url(#g)"/>\n'
    f'<path d="{d(GEM_SHINE)}" fill="#FFFFFF" opacity="0.4"/>\n'
    f'</svg>\n')


def path_data(pts):
    return ' '.join(f'{"M" if i == 0 else "L"}{x:g},{y:g}' for i, (x, y) in enumerate(pts)) + 'Z'


def fit(target, pad):
    x0, y0, x1, y1 = BBOX
    scale = min((target - 2 * pad) / (x1 - x0), (target - 2 * pad) / (y1 - y0))
    dx = (target - (x1 - x0) * scale) / 2 - x0 * scale
    dy = (target - (y1 - y0) * scale) / 2 - y0 * scale
    return scale, dx, dy


def place(pts, scale, dx, dy):
    return [(round(x * scale + dx, 2), round(y * scale + dy, 2)) for x, y in pts]


def vector(path, entries, size=108):
    """VectorDrawable. Cada entrada é (pontos, cor ou 'GRADIENT', alpha)."""
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           f'    android:width="{size}dp" android:height="{size}dp"',
           f'    android:viewportWidth="{size}" android:viewportHeight="{size}">']
    for pts, fill, alpha in entries:
        if fill == 'GRADIENT':
            out += [f'    <path android:pathData="{path_data(pts)}">',
                    '        <aapt:attr name="android:fillColor">',
                    f'            <gradient android:type="linear" android:startX="{size*0.2:g}" android:startY="{size*0.8:g}"'
                    f' android:endX="{size*0.8:g}" android:endY="{size*0.2:g}">',
                    f'                <item android:offset="0" android:color="{SECOND}" />',
                    f'                <item android:offset="1" android:color="{MAIN}" />',
                    '            </gradient>',
                    '        </aapt:attr>',
                    '    </path>']
        else:
            extra = f' android:fillAlpha="{alpha}"' if alpha else ''
            out.append(f'    <path android:fillColor="{fill}"{extra} android:pathData="{path_data(pts)}" />')
    out.append('</vector>')
    path.write_text('\n'.join(out) + '\n')


def symbol(path, entries, width, height, size_w):
    """Marca solta (sem fundo), na proporção do desenho — usada dentro do app."""
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           f'    android:width="{size_w}dp" android:height="{size_w * height / width:.2f}dp"',
           f'    android:viewportWidth="{width}" android:viewportHeight="{height}">']
    for pts, fill, alpha in entries:
        if fill == 'GRADIENT':
            out += [f'    <path android:pathData="{path_data(pts)}">',
                    '        <aapt:attr name="android:fillColor">',
                    f'            <gradient android:type="linear" android:startX="{width*0.25:g}" android:startY="{height*0.85:g}"'
                    f' android:endX="{width*0.8:g}" android:endY="{height*0.15:g}">',
                    f'                <item android:offset="0" android:color="{SECOND}" />',
                    f'                <item android:offset="1" android:color="{MAIN}" />',
                    '            </gradient>',
                    '        </aapt:attr>',
                    '    </path>']
        else:
            extra = f' android:fillAlpha="{alpha}"' if alpha else ''
            out.append(f'    <path android:fillColor="{fill}"{extra} android:pathData="{path_data(pts)}" />')
    out.append('</vector>')
    path.write_text('\n'.join(out) + '\n')


def rsvg(src, dest, size, background=None):
    if not shutil.which('rsvg-convert'):
        sys.exit('rsvg-convert não encontrado (instale librsvg2-bin).')
    cmd = ['rsvg-convert', '-w', str(size), '-o', str(dest)]
    if background:
        cmd += ['-b', background]
    cmd.append(str(src))
    subprocess.run(cmd, check=True)


def main():
    out = pathlib.Path(tempfile.mkdtemp(prefix='valky-brand-'))
    svg = out / 'valky-mark.svg'
    svg.write_text(SVG)

    for target in ['web/public/valky-mark.svg', 'web/viewer-public/valky-mark.svg']:
        (ROOT / target).write_text(SVG)

    rsvg(svg, ROOT / 'web/public/valky-mark.png', 1024)
    rsvg(svg, ROOT / 'web/public/favicon.png', 512)
    rsvg(svg, ROOT / 'web/public/apple-touch-icon.png', 180, DARK)

    square = out / 'valky-mark-square.svg'
    square.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">'
                      f'<image x="0" y="{(512 - 512 * VH / VW) / 2:g}" width="512" height="{512 * VH / VW:g}"'
                      f' href="{svg}"/></svg>')
    rsvg(square, ROOT / 'mobile/app/src/main/res/drawable-nodpi/valky_mark.png', 512)

    res = ROOT / 'mobile/app/src/main/res/drawable'
    scale, dx, dy = fit(108, 21)
    adaptive = [(place(LEFT, scale, dx, dy), 'GRADIENT', None), (place(RIGHT, scale, dx, dy), 'GRADIENT', None),
                (place(CREASE_LEFT, scale, dx, dy), CREASE, '0.35'), (place(CREASE_RIGHT, scale, dx, dy), CREASE, '0.35'),
                (place(GEM, scale, dx, dy), GEM_DARK, None), (place(GEM_SHINE, scale, dx, dy), '#FFFFFF', '0.4')]
    vector(res / 'valky_launcher_foreground.xml', adaptive)
    vector(res / 'valky_map_marker.xml', adaptive)

    mono = [(place(LEFT, scale, dx, dy), '#FFFFFFFF', None), (place(RIGHT, scale, dx, dy), '#FFFFFFFF', None),
            (place(GEM, scale, dx, dy), '#FFFFFFFF', None)]
    vector(res / 'valky_launcher_monochrome.xml', mono)

    # Ícone legado (Android 7 e anterior): fundo escuro arredondado + marca.
    vector(res / 'valky_mark_vector.xml',
           [(place(LEFT, scale, dx, dy), 'GRADIENT', None), (place(RIGHT, scale, dx, dy), 'GRADIENT', None),
            (place(GEM, scale, dx, dy), GEM_DARK, None), (place(GEM_SHINE, scale, dx, dy), '#FFFFFF', '0.4')])
    legacy = res / 'valky_mark_vector.xml'
    body = legacy.read_text().replace(
        f'    android:viewportWidth="{108}" android:viewportHeight="{108}">',
        f'    android:viewportWidth="{108}" android:viewportHeight="{108}">\n'
        f'    <path android:fillColor="{DARK}" android:pathData="M14,0h80a14,14 0 0 1 14,14v80a14,14 0 0 1 -14,14H14A14,14 0 0 1 0,94V14A14,14 0 0 1 14,0Z" />')
    legacy.write_text(body)

    nscale, ndx, ndy = fit(108, 30)
    vector(res / 'ic_notification.xml',
           [(place(LEFT, nscale, ndx, ndy), '#FFFFFFFF', None), (place(RIGHT, nscale, ndx, ndy), '#FFFFFFFF', None),
            (place(GEM, nscale, ndx, ndy), '#FFFFFFFF', None)])

    (res / 'valky_launcher_background.xml').write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        f'    <solid android:color="{DARK}" />\n'
        '</shape>\n')

    symbol(res / 'valky_symbol.xml',
           [(LEFT, 'GRADIENT', None), (RIGHT, 'GRADIENT', None),
            (CREASE_LEFT, CREASE, '0.35'), (CREASE_RIGHT, CREASE, '0.35'),
            (GEM, GEM_DARK, None), (GEM_SHINE, '#FFFFFF', '0.4')], 455, 335, 96)

    shutil.rmtree(out, ignore_errors=True)
    print('ativos da marca gerados')


if __name__ == '__main__':
    main()
