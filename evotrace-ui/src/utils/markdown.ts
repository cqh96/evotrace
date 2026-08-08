import MarkdownIt from 'markdown-it'

// html:false + markdown-it 默认 validateLink（拦截 javascript: 等协议）→ 安全 v-html，无需 DOMPurify
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

export function renderMarkdown(source: string | null | undefined): string {
  return md.render(source ?? '')
}
