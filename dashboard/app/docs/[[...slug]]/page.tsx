import Link from "next/link";
import Image from "next/image";
import { notFound } from "next/navigation";
import { cache } from "react";
import { loadDocuments, docGroups, primaryGroups, searchDocuments, documentExcerpt, type DocEntry } from "../../../lib/docs-catalog";
import { prepareDocument } from "../../../lib/docs-markdown";
import { DocumentBody } from "../document-body";
import { ResponsiveOutline } from "../responsive-outline";

type Props = { params: Promise<{ slug?: string[] }>; searchParams: Promise<{ q?: string; group?: string; records?: string }> };
const getDocuments = cache(() => loadDocuments());
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: Props) {
  const { slug } = await params;
  const doc = slug && (await getDocuments()).find(doc => doc.href === `/docs/${slug.map(encodeURIComponent).join("/")}`);
  return { title: `${doc ? doc.title : "프로젝트 문서"} · Edge AI Docs` };
}

function Search({ query = "", group = "", records = false }: { query?: string; group?: string; records?: boolean }) {
  return <section className="docs-search" aria-label="문서 검색">
    <div className="docs-search-heading"><strong>문서 검색</strong><span>제목, 경로, 본문에서 찾습니다.</span></div>
    <form action="/docs#documents" role="search"><label className="docs-sr-only" htmlFor="doc-query">문서 검색어</label>
      <input id="doc-query" name="q" type="search" placeholder="예: 센서, GPU, 배포, 가상 디바이스" defaultValue={query} maxLength={200} />
      <label className="docs-sr-only" htmlFor="doc-group">검색 범위</label><select id="doc-group" name="group" defaultValue={group}><option value="">모든 분야</option>{docGroups.map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
      <button type="submit">검색</button>
      <label className="docs-record-toggle"><input type="checkbox" name="records" value="1" defaultChecked={records} /> 심화 자료·개발 기록 포함</label>
    </form>
  </section>;
}

function DocumentCards({ documents, query }: { documents: DocEntry[]; query: string }) {
  return <ul className="docs-card-grid">{documents.map(doc => <li key={doc.source}><Link prefetch={false} href={doc.href}>
    <strong>{doc.title}</strong>{(query || doc.description) && <p>{query ? documentExcerpt(doc, query) : doc.description}</p>}<small>{doc.source}</small><span aria-hidden="true">→</span>
  </Link></li>)}</ul>;
}

export default async function DocsPage({ params, searchParams }: Props) {
  const [{ slug }, search, documents] = await Promise.all([params, searchParams, getDocuments()]);
  const query = typeof search.q === "string" ? search.q.trim().slice(0, 200) : "";
  const group = docGroups.some(([key]) => key === search.group) ? search.group! : "";
  const records = search.records === "1" || Boolean(group && !primaryGroups.has(group));
  if (slug?.length) {
    const document = documents.find(doc => doc.href === `/docs/${slug.map(encodeURIComponent).join("/")}`);
    if (!document) notFound();
    const { headings } = prepareDocument(document.content);
    const groupName = docGroups.find(([key]) => key === document.group)?.[1];
    const siblings = documents.filter(doc => doc.primary);
    const position = siblings.findIndex(doc => doc.source === document.source);
    const previous = position > 0 ? siblings[position - 1] : undefined;
    const next = position >= 0 ? siblings[position + 1] : undefined;
    return <main id="main-content" className="docs-reader">
      <Search />
      <div className="docs-reading-grid">
        <aside className="docs-sidebar"><ResponsiveOutline key={document.href} label="문서 목록"><nav aria-label="문서 목록">{docGroups.map(([key, label]) => {
          const entries = documents.filter(doc => doc.group === key && (doc.primary || key === document.group));
          return entries.length > 0 && <details key={`${document.href}-${key}`} open={key === document.group || key === "getting-started"}><summary>{label}<small>{entries.length}</small></summary>{entries.map(doc => <Link prefetch={false} key={doc.source} href={doc.href} aria-current={doc.href === document.href ? "page" : undefined}>{doc.title}</Link>)}</details>;
        })}<Link href="/docs?records=1#documents">심화 자료·개발 기록 모두 보기 →</Link></nav></ResponsiveOutline></aside>
        <article className="docs-article">
          <nav className="docs-breadcrumb" aria-label="현재 문서 위치"><Link href="/docs">문서</Link><span>/</span><Link href={`/docs?group=${document.group}#documents`}>{groupName}</Link></nav>
          <div className="docs-file-meta"><code>{document.source}</code><span>파일 수정 <time dateTime={document.modified}>{new Date(document.modified).toLocaleDateString("ko-KR", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" })}</time></span></div>
          {["evidence", "history", "adr"].includes(document.group) && <p className="docs-record-note">작성 당시의 기록입니다. 현재 구현 상태는 <Link href="/docs/project/PROGRESS">현재 개발 상태</Link>에서 확인하세요.</p>}
          {!document.primary && !["evidence", "history", "adr"].includes(document.group) && <p className="docs-record-note">개발·운영 심화 자료입니다. 적용 조건과 문서의 기준 시점을 확인하세요. <Link href="/docs/reference/support">현재 지원 범위 →</Link></p>}
          <DocumentBody document={document} documents={documents} />
          {(previous || next) && <nav className="docs-sequence" aria-label="문서 읽는 순서">{previous ? <Link href={previous.href}><small>이전 문서</small>{previous.title}</Link> : <span />}{next && <Link href={next.href}><small>다음 문서</small>{next.title} →</Link>}</nav>}
          <div className="docs-article-bottom"><Link href={`/docs?group=${document.group}#documents`}>← {groupName} 목록</Link><a href="#main-content">맨 위로 ↑</a></div>
        </article>
        {headings.length > 0 && <aside className="docs-toc"><ResponsiveOutline key={document.href} label="이 문서"><nav aria-label="이 문서의 목차">{headings.map(heading => <a key={heading.id} href={`#${heading.id}`} data-depth={heading.depth}>{heading.title}</a>)}</nav></ResponsiveOutline></aside>}
      </div>
    </main>;
  }
  const filtered = searchDocuments(documents, query, group, records);
  const filteredView = Boolean(query || group || records);
  return <main id="main-content" className="docs-home">
    {!filteredView && <>
      <section className="docs-hero"><div><p className="docs-eyebrow">LEARN · BUILD · OPERATE</p><h1>Edge AI <br />플랫폼 문서</h1><p className="docs-subtitle">플랫폼의 개념을 이해하고, 장치를 연결하고,<br />서비스를 배포·운영하는 방법을 알아보세요.</p><Link href="/docs/getting-started/overview">EdgeAI 알아보기 →</Link></div>
        <Image src="/docs/platform-concept.png" alt="센서와 엣지 컴퓨터, 중앙 플랫폼이 연결된 Edge AI 개념도" width={1536} height={864} priority unoptimized />
      </section>
      <section className="docs-start" aria-labelledby="docs-start-title"><p className="docs-eyebrow">START HERE</p><h2 id="docs-start-title">무엇을 하시나요?</h2><div className="docs-quick-grid">
        {[
          ["01 · DEVELOP", "로컬에서 시작하기", "실행 환경과 개발 명령을 확인합니다.", "/docs/guides/local-development"],
          ["02 · TUTORIAL", "첫 프로필 등록하기", "짧은 실습으로 등록·조회·정리 흐름을 익힙니다.", "/docs/getting-started/first-profile"],
          ["03 · DEPLOY", "플랫폼 배포하기", "CI/CD, Kubernetes와 Argo CD 흐름을 확인합니다.", "/docs/operations/cicd"],
          ["04 · REFERENCE", "API와 설정 찾기", "관리 API와 환경변수의 정확한 계약을 확인합니다.", "/docs/reference/api"],
        ].map(([label, title, text, href]) => <Link key={href} href={href}><small>{label}</small><strong>{title}</strong><p>{text}</p><span aria-hidden="true">→</span></Link>)}
      </div></section>
    </>}
    {filteredView && <div className="docs-results-title"><Link href="/docs">← 문서 홈</Link><h1>문서 찾기</h1></div>}
    <Search query={query} group={group} records={records} />
    <section id="documents" className="docs-catalog" aria-labelledby="docs-catalog-title"><p className="docs-eyebrow">REFERENCE</p><h2 id="docs-catalog-title">{filteredView ? "검색 결과" : "프로젝트 문서"}<span>{filtered.length}개</span></h2>
      {query && <p className="docs-result-query">‘{query}’ 검색 결과</p>}
      {!records && <p className="docs-result-query">사용·운영 안내를 읽는 순서로 정리했습니다. <Link href="/docs?records=1#documents">전체 {documents.length}개 문서와 개발 기록 보기 →</Link></p>}
      {filteredView && <Link href="/docs#documents">검색 초기화</Link>}
      {filtered.length === 0 && <div className="docs-empty"><h3>일치하는 문서가 없습니다.</h3><p>다른 검색어를 입력하거나 검색 범위를 전체 문서로 바꿔보세요.</p></div>}
      {docGroups.map(([key, label]) => {
        const entries = filtered.filter(doc => doc.group === key);
        if (!entries.length) return null;
        return <details className="docs-collection" key={`${query}-${group}-${key}`} open={filteredView || !["evidence", "history", "adr"].includes(key)}><summary><h3>{label}</h3><span>{entries.length}개 문서</span></summary><DocumentCards documents={entries} query={query} /></details>;
      })}
    </section>
  </main>;
}
