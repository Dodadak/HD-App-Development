"""배지 SVG(원·경로·다각형·그룹만 사용)를 Android VectorDrawable XML로 바꾼다."""
import sys, os, re
import xml.etree.ElementTree as ET

def tag(e): return e.tag.split('}')[-1]

def color(c, alpha):
    c = c.strip()
    if len(c) == 4: c = '#' + ''.join(ch * 2 for ch in c[1:])
    return c.upper(), alpha

def circle_d(cx, cy, r):
    return f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0Z"

def walk(e, inh, out, depth):
    st = dict(inh)
    for k in ('fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin'):
        if k in e.attrib: st[k] = e.attrib[k]
    if 'opacity' in e.attrib: st['opacity'] = st.get('opacity', 1.0) * float(e.attrib['opacity'])
    t = tag(e)
    pad = '    ' * depth
    if t in ('svg', 'g'):
        tr = e.attrib.get('transform')
        m = re.match(r'translate\(([-\d.]+)[ ,]*([-\d.]*)\)', tr or '')
        tx, ty = (float(m.group(1)), float(m.group(2) or 0)) if m else (0, 0)
        grouped = t == 'g' and (tx or ty)
        if grouped:
            out.append(f'{pad}<group android:translateX="{tx}" android:translateY="{ty}">')
        for ch in e: walk(ch, st, out, depth + (1 if grouped else 0))
        if grouped: out.append(f'{pad}</group>')
        return
    if t == 'circle':
        d = circle_d(float(e.attrib['cx']), float(e.attrib['cy']), float(e.attrib['r']))
    elif t == 'path':
        d = e.attrib['d']
    elif t == 'polygon':
        pts = e.attrib['points'].split()
        d = 'M' + 'L'.join(pts) + 'Z'
    else:
        raise SystemExit(f'지원하지 않는 요소: {t}')
    a = st.get('opacity', 1.0)
    attrs = [f'android:pathData="{d}"']
    fill = st.get('fill', '#000000')
    if fill != 'none':
        attrs.append(f'android:fillColor="{color(fill, a)[0]}"')
        if a != 1.0: attrs.append(f'android:fillAlpha="{a:g}"')
    stroke = st.get('stroke')
    if stroke and stroke != 'none':
        attrs.append(f'android:strokeColor="{color(stroke, a)[0]}"')
        attrs.append(f'android:strokeWidth="{st.get("stroke-width", "1")}"')
        if a != 1.0: attrs.append(f'android:strokeAlpha="{a:g}"')
        if 'stroke-linecap' in st: attrs.append(f'android:strokeLineCap="{st["stroke-linecap"]}"')
        if 'stroke-linejoin' in st: attrs.append(f'android:strokeLineJoin="{st["stroke-linejoin"]}"')
    out.append(pad + '<path ' + ('\n' + pad + '    ').join(attrs) + ' />')

def convert(src, dst, label):
    root = ET.parse(src).getroot()
    out = []
    walk(root, {}, out, 1)
    with open(dst, 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write(f'<!-- {label} — 배지 디자인 SVG에서 변환 (scripts/svg2vd.py) -->\n')
        f.write('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="120dp"\n    android:height="120dp"\n'
                '    android:viewportWidth="120"\n    android:viewportHeight="120">\n')
        f.write('\n'.join(out) + '\n</vector>\n')

if __name__ == '__main__':
    src_dir, res_dir = sys.argv[1], sys.argv[2]
    n = 0
    for state in ('earned', 'locked'):
        for name in sorted(os.listdir(os.path.join(src_dir, state))):
            if not name.endswith('.svg'): continue
            base = name[:-4]
            dst = f'{base}.xml' if state == 'earned' else f'{base}_locked.xml'
            convert(os.path.join(src_dir, state, name), os.path.join(res_dir, dst), f'{base} ({"받음" if state == "earned" else "잠김"})')
            n += 1
    print(f'{n}개 변환')
