#!/usr/bin/env python3
"""Build the Chinese PDF manual from docs/USER_MANUAL.md.

Usage: uv run --with reportlab --with pillow python scripts/build-manual.py
"""

from __future__ import annotations

import argparse
from html import escape
from pathlib import Path
import re

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    CondPageBreak,
    HRFlowable,
    Image,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

ROOT = Path(__file__).resolve().parents[1]
BLUE = colors.HexColor('#1D4ED8')
INK = colors.HexColor('#172438')
MUTED = colors.HexColor('#64748B')


def inline(text: str) -> str:
    text = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)
    text = escape(text)
    text = re.sub(r'`([^`]+)`', r'<font color="#1D4ED8">\1</font>', text)
    return re.sub(r'\*\*([^*]+)\*\*', r'<b>\1</b>', text)


def build(font: Path, output: Path) -> None:
    pdfmetrics.registerFont(TTFont('ManualCJK', str(font), subfontIndex=0))
    pdfmetrics.registerFontFamily('ManualCJK', normal='ManualCJK', bold='ManualCJK')
    base = dict(fontName='ManualCJK', textColor=INK, wordWrap='CJK')
    styles = {
        'body': ParagraphStyle('Body', fontSize=10, leading=16, spaceAfter=7, **base),
        'title': ParagraphStyle('Title', fontSize=27, leading=38, spaceAfter=20, **base),
        'h2': ParagraphStyle('Chapter', fontSize=19, leading=27, spaceAfter=15,
                             keepWithNext=True, **base),
        'h3': ParagraphStyle('Section', fontSize=12, leading=18, spaceBefore=9,
                             spaceAfter=7, keepWithNext=True, **base),
        'cell': ParagraphStyle('Cell', fontSize=9, leading=14, **base),
        'caption': ParagraphStyle('Caption', fontSize=8, leading=12, alignment=TA_CENTER,
                                  spaceBefore=5, spaceAfter=10,
                                  fontName='ManualCJK', textColor=MUTED, wordWrap='CJK'),
        'bullet': ParagraphStyle('Bullet', fontSize=10, leading=16, leftIndent=12,
                                 firstLineIndent=-10, spaceAfter=5, **base),
    }
    width = A4[0] - 36 * mm
    story = []
    lines = (ROOT / 'docs/USER_MANUAL.md').read_text(encoding='utf-8').splitlines()
    chapters = [line[3:] for line in lines if line.startswith('## ')]

    def table(rows: list[list[str]]) -> Table:
        count = len(rows[0])
        ratios = [0.29, 0.71] if count == 2 else [0.16, 0.36, 0.48]
        if len(ratios) != count:
            ratios = [1 / count] * count
        cells = [[Paragraph(inline(value), styles['cell']) for value in row] for row in rows]
        result = Table(cells, colWidths=[width * r for r in ratios], repeatRows=1,
                       hAlign='LEFT', spaceAfter=12)
        result.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#EAF0FF')),
            ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, colors.HexColor('#F6F8FC')]),
            ('VALIGN', (0, 0), (-1, -1), 'TOP'),
            ('TOPPADDING', (0, 0), (-1, -1), 7),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 7),
            ('LEFTPADDING', (0, 0), (-1, -1), 9),
            ('RIGHTPADDING', (0, 0), (-1, -1), 9),
            ('LINEBELOW', (0, 0), (-1, 0), 0.6, colors.HexColor('#CAD6ED')),
        ]))
        return result

    i = 0
    cover_done = False
    while i < len(lines):
        line = lines[i].strip()
        i += 1
        if not line:
            continue
        if line.startswith('# '):
            story.extend([Spacer(1, 28 * mm), Paragraph('现场操作指南', styles['h3']),
                          Paragraph(inline(line[2:]), styles['title']),
                          HRFlowable(width='100%', thickness=2, color=BLUE), Spacer(1, 12 * mm)])
        elif line.startswith('## '):
            if not cover_done:
                story.append(Spacer(1, 8 * mm))
                story.append(table([
                    ['已验证 PDA', '海康机器人 Hikrobot MV-IDP5204'],
                    ['已验证打印机', '德佟 DP30S，蓝牙连接与实际出纸已验证'],
                    ['标签规格', '30 mm × 20 mm'],
                ]))
                story.append(Paragraph('手册目录', styles['h3']))
                for chapter in chapters:
                    story.append(Paragraph(inline(chapter), styles['body']))
                cover_done = True
                story.append(PageBreak())
            else:
                story.extend([Spacer(1, 6 * mm), CondPageBreak(70 * mm)])
            story.append(Paragraph(inline(line[3:]), styles['h2']))
        elif line.startswith('### '):
            story.append(Paragraph(inline(line[4:]), styles['h3']))
        elif line.startswith('|'):
            rows = []
            while True:
                if not re.fullmatch(r'[|\s:\-]+', line):
                    rows.append([cell.strip() for cell in line.strip('|').split('|')])
                if i == len(lines) or not lines[i].strip().startswith('|'):
                    break
                line = lines[i].strip()
                i += 1
            story.append(table(rows))
        elif (match := re.fullmatch(r'!\[([^\]]*)\]\(([^)]+)\)', line)):
            figure = Image(str(ROOT / 'docs' / match[2]))
            scale = min(91 * mm / figure.imageHeight, 100 * mm / figure.imageWidth)
            figure.drawWidth = figure.imageWidth * scale
            figure.drawHeight = figure.imageHeight * scale
            figure.hAlign = 'CENTER'
            story.append(KeepTogether([Spacer(1, 4 * mm), figure,
                                      Paragraph(inline(match[1]), styles['caption'])]))
        elif line.startswith('- '):
            story.append(Paragraph('• ' + inline(line[2:]), styles['bullet']))
        elif re.match(r'^\d+\. ', line):
            story.append(Paragraph(inline(line), styles['bullet']))
        else:
            story.append(Paragraph(inline(line), styles['body']))

    def decorate(canvas, doc):
        canvas.saveState()
        canvas.setFont('ManualCJK', 8)
        canvas.setFillColor(MUTED)
        canvas.drawString(18 * mm, A4[1] - 12 * mm, 'PDA 扫码登记工具  |  v0.1.13')
        canvas.drawRightString(A4[0] - 18 * mm, A4[1] - 12 * mm, 'MV-IDP5204 + DP30S')
        canvas.setStrokeColor(colors.HexColor('#DCE4F0'))
        canvas.line(18 * mm, 15 * mm, A4[0] - 18 * mm, 15 * mm)
        canvas.drawString(18 * mm, 10 * mm, '操作手册 · 2026-11-17')
        canvas.drawRightString(A4[0] - 18 * mm, 10 * mm, str(doc.page))
        canvas.restoreState()

    output.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(str(output), pagesize=A4, leftMargin=18 * mm,
                            rightMargin=18 * mm, topMargin=22 * mm, bottomMargin=22 * mm,
                            title='PDA扫码登记工具 操作手册 v0.1.13', author='sanduo-656')
    doc.build(story, onFirstPage=decorate, onLaterPages=decorate)
    print(f'Generated: {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', type=Path, default=Path('C:/Windows/Fonts/simsun.ttc'))
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/PDA扫码登记-操作手册.pdf')
    args = parser.parse_args()
    if not args.font.is_file():
        parser.error('Chinese TrueType font not found; pass --font /path/to/chinese.ttf')
    build(args.font, args.output)
