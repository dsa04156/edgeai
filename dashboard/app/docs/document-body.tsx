import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeSlug from "rehype-slug";
import { Children, isValidElement } from "react";
import { resolveDocumentLink, type DocEntry } from "../../lib/docs-catalog";
import { MermaidDiagram } from "./mermaid-diagram";

export function DocumentBody({ document, documents }: { document: DocEntry; documents: DocEntry[] }) {
  if (/\.ya?ml$/i.test(document.source)) return <div className="docs-prose"><h1>{document.title}</h1><pre><code>{document.content}</code></pre></div>;
  return <div className="docs-prose"><ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeSlug]} skipHtml
    urlTransform={url => resolveDocumentLink(url, document.source, documents) || ""}
    components={{
      a: ({ href, children }) => href ? <a href={href} rel={/^https?:/.test(href) ? "noreferrer" : undefined}>{children}</a> : <span className="docs-unlinked" title="저장소에서 확인하는 파일">{children}</span>,
      img: ({ alt }) => <span className="docs-unlinked">{alt || "이미지 (원본 문서 참조)"}</span>,
      table: ({ children }) => <div className="docs-table" tabIndex={0} role="region" aria-label="문서 표"><table>{children}</table></div>,
      pre: ({ children }) => {
        const child = Children.toArray(children)[0];
        if (isValidElement<{ className?: string; children?: string }>(child) && child.props.className === "language-mermaid")
          return <MermaidDiagram source={String(child.props.children || "")} />;
        return <pre tabIndex={0}>{children}</pre>;
      },
    }}>{document.content}</ReactMarkdown></div>;
}
