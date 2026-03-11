/**
 * 移除 Markdown 格式符号，返回纯文本（用于列表预览）
 */
export function stripMarkdown(text: string): string {
  if (!text) return ''
  return text
    // 移除代码块 ```...```
    .replace(/```[\s\S]*?```/g, '')
    // 移除行内代码 `...`
    .replace(/`([^`]+)`/g, '$1')
    // 移除标题符号 ### / ## / #
    .replace(/^#{1,6}\s+/gm, '')
    // 移除加粗 **...**
    .replace(/\*\*(.+?)\*\*/g, '$1')
    // 移除斜体 *...*
    .replace(/\*(.+?)\*/g, '$1')
    // 移除删除线 ~~...~~
    .replace(/~~(.+?)~~/g, '$1')
    // 移除链接 [text](url) → text
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    // 移除图片 ![alt](url)
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    // 移除无序列表标记 - / * / +
    .replace(/^[\s]*[-*+]\s+/gm, '')
    // 移除有序列表标记 1. 2. 3.
    .replace(/^[\s]*\d+\.\s+/gm, '')
    // 移除引用 >
    .replace(/^>\s*/gm, '')
    // 移除水平线
    .replace(/^[-*_]{3,}$/gm, '')
    // 压缩多余空行
    .replace(/\n{2,}/g, '\n')
    .trim()
}

/**
 * 清洗 AI 返回的 Markdown 内容：剥离外层 ```markdown ``` 包裹
 * 部分 AI 回复会将整个内容包裹在代码块中，导致渲染异常
 */
export function cleanMarkdown(text: string): string {
  if (!text) return ''
  // 匹配以 ```markdown 或 ```md 或 ``` 开头，以 ``` 结尾的外层包裹
  const wrapped = text.match(/^\s*```(?:markdown|md)?\s*\n([\s\S]*?)\n\s*```\s*$/)
  if (wrapped) {
    return wrapped[1]
  }
  return text
}
