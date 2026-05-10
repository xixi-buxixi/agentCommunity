const escapeHtml = (value = '') => String(value)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const renderInline = (value) => escapeHtml(value)
  .replace(/`([^`]+)`/g, '<code>$1</code>')
  .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  .replace(/\*([^*]+)\*/g, '<em>$1</em>')
  .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')

export const renderMarkdown = (content = '') => {
  const lines = String(content || '').replace(/\r\n/g, '\n').split('\n')
  const html = []
  let inCode = false
  let inList = false
  let paragraph = []

  const flushParagraph = () => {
    if (paragraph.length) {
      html.push(`<p>${renderInline(paragraph.join(' '))}</p>`)
      paragraph = []
    }
  }

  const closeList = () => {
    if (inList) {
      html.push('</ul>')
      inList = false
    }
  }

  lines.forEach((line) => {
    if (line.trim().startsWith('```')) {
      flushParagraph()
      closeList()
      if (inCode) {
        html.push('</code></pre>')
      } else {
        html.push('<pre><code>')
      }
      inCode = !inCode
      return
    }

    if (inCode) {
      html.push(`${escapeHtml(line)}\n`)
      return
    }

    const trimmed = line.trim()
    if (!trimmed) {
      flushParagraph()
      closeList()
      return
    }

    const heading = trimmed.match(/^(#{1,3})\s+(.+)$/)
    if (heading) {
      flushParagraph()
      closeList()
      const level = heading[1].length
      html.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      return
    }

    const quote = trimmed.match(/^>\s?(.+)$/)
    if (quote) {
      flushParagraph()
      closeList()
      html.push(`<blockquote>${renderInline(quote[1])}</blockquote>`)
      return
    }

    const listItem = trimmed.match(/^[-*]\s+(.+)$/)
    if (listItem) {
      flushParagraph()
      if (!inList) {
        html.push('<ul>')
        inList = true
      }
      html.push(`<li>${renderInline(listItem[1])}</li>`)
      return
    }

    closeList()
    paragraph.push(trimmed)
  })

  flushParagraph()
  closeList()
  if (inCode) {
    html.push('</code></pre>')
  }

  return html.join('')
}
