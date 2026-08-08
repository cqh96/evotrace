/**
 * 原型导出为独立 HTML（前端 Blob 下载，不走服务端）。
 * XSS 防护：escapeHtml 转义全部文本/属性；img src 仅允许 http/https/data:image，
 * 其余渲染占位框；跳转仅通过 data-link + 预置脚本切页，不执行任何用户输入。
 */
import type { PrototypeElementProps, PrototypePage } from '../api/pm'

export function escapeHtml(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 数值化坐标/尺寸，非法值回落默认。 */
function num(value: unknown, dflt: number): number {
  const n = Number(value)
  return Number.isFinite(n) && n >= 0 ? Math.round(n) : dflt
}

/** img src 协议白名单，否则返回 null（渲染占位框）。 */
function safeImgSrc(src: unknown): string | null {
  const s = String(src ?? '').trim()
  if (s === '') return null
  if (/^https?:\/\//i.test(s) || /^data:image\//i.test(s)) return s
  return null
}

function optionsText(props: PrototypeElementProps): string {
  return String(props.options ?? '')
}

function renderElement(el: { type: string; x: number; y: number; w: number; h: number; props: PrototypeElementProps; linkTo?: string }): string {
  const { type, props } = el
  const x = num(el.x, 0)
  const y = num(el.y, 0)
  const w = num(el.w, 120)
  const h = num(el.h, 40)
  const style = `left:${x}px;top:${y}px;width:${w}px;height:${h}px`
  const link = el.linkTo ? ` data-link="${escapeHtml(el.linkTo)}"` : ''
  const text = escapeHtml(props.text)

  switch (type) {
    case 'BUTTON':
      return `<button class="el but" style="${style}"${link}>${text || '按钮'}</button>`
    case 'INPUT':
      return `<input class="el input" style="${style}" placeholder="${escapeHtml(props.placeholder)}">`
    case 'SELECTOR':
      return `<select class="el input" style="${style}"><option>${escapeHtml(props.placeholder) || '请选择'}</option>${optionsText(props).split('\n').filter(Boolean).map((o) => `<option>${escapeHtml(o)}</option>`).join('')}</select>`
    case 'NAV': {
      const items = optionsText(props).split('\n').filter(Boolean)
      return `<div class="el nav" style="left:0;top:${y}px;width:100%;height:${num(h, 56)}px"><b>${text || escapeHtml(props.brand) || '品牌'}</b>${items.map((o) => `<span>${escapeHtml(o)}</span>`).join('')}</div>`
    }
    case 'TABLE': {
      const cols = num(props.columns, 3)
      const rows = num(props.rows, 2)
      let table = `<table class="el table" style="${style}">`
      for (let r = 0; r < rows; r++) {
        table += '<tr>'
        for (let c = 0; c < cols; c++) table += `<td>${text && r === 0 && c === 0 ? text : ''}</td>`
        table += '</tr>'
      }
      return table + '</table>'
    }
    case 'LIST':
      return `<div class="el list" style="${style}">${optionsText(props).split('\n').filter(Boolean).map((o) => `<div>${escapeHtml(o)}</div>`).join('')}</div>`
    case 'IMAGE': {
      const src = safeImgSrc(props.src)
      return src
        ? `<img class="el img" style="${style}" src="${escapeHtml(src)}" alt="">`
        : `<div class="el img placeholder" style="${style}">图片</div>`
    }
    case 'CONTAINER':
      return `<div class="el container" style="${style}"${link}>${text}</div>`
    case 'TEXT':
    default:
      return `<div class="el text" style="${style}"${link}>${text}</div>`
  }
}

function renderPage(page: PrototypePage, index: number): string {
  const w = num(page.width, 375)
  const h = num(page.height, 812)
  const active = index === 0 ? ' active' : ''
  const body = (page.elements ?? [])
    .map((el) => renderElement(el))
    .join('')
  return `<section class="page${active}" data-page="${escapeHtml(page.id)}" style="width:${w}px;height:${h}px">${body}</section>`
}

export function buildStandaloneHtml(title: string, pages: PrototypePage[]): string {
  const safeTitle = escapeHtml(title || '原型')
  const body = (pages ?? []).map(renderPage).join('')
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${safeTitle}</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
  body { background: #e8ecf1; padding: 24px; }
  .frame { display: flex; gap: 24px; flex-wrap: wrap; justify-content: center; }
  .page { display: none; background: #fff; border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,.15); position: relative; overflow: hidden; }
  .page.active { display: block; }
  .el { position: absolute; }
  .but { background: #409eff; color: #fff; border: 0; border-radius: 6px; font-size: 14px; cursor: pointer; }
  .input { border: 1px solid #c0c4cc; border-radius: 6px; font-size: 14px; padding: 0 10px; background: #fff; color: #303133; }
  .text { color: #303133; font-size: 14px; word-break: break-word; overflow: hidden; }
  .container { border: 1px dashed #c0c4cc; border-radius: 8px; background: #f7f8fa; color: #606266; font-size: 13px; padding: 8px; overflow: hidden; }
  .nav { background: #fff; border-bottom: 1px solid #e4e7ed; display: flex; align-items: center; gap: 16px; padding: 0 16px; font-size: 14px; z-index: 2; }
  .nav b { color: #303133; }
  .nav span { color: #606266; }
  .table { border-collapse: collapse; font-size: 12px; color: #303133; }
  .table td { border: 1px solid #dcdfe6; padding: 4px 8px; }
  .list div { padding: 8px 10px; border-bottom: 1px solid #f0f2f5; font-size: 14px; color: #303133; }
  .img { object-fit: cover; border-radius: 6px; background: #f0f2f5; }
  .img.placeholder { display: flex; align-items: center; justify-content: center; color: #909399; font-size: 12px; background: #f0f2f5; border: 1px dashed #dcdfe6; }
</style>
</head>
<body>
<div class="frame">${body}</div>
<script>
  document.querySelectorAll('[data-link]').forEach(function (el) {
    el.addEventListener('click', function () {
      var target = el.getAttribute('data-link')
      document.querySelectorAll('.page').forEach(function (p) {
        p.classList.toggle('active', p.getAttribute('data-page') === target)
      })
    })
  })
</script>
</body>
</html>`
}

export function downloadPrototypeHtml(title: string, pages: PrototypePage[]): void {
  const html = buildStandaloneHtml(title, pages)
  const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${title || 'prototype'}.html`
  a.click()
  URL.revokeObjectURL(url)
}
