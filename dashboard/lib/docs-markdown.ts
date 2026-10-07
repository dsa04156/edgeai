import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import { toString } from "mdast-util-to-string";
import GithubSlugger from "github-slugger";

export function prepareDocument(content: string) {
  const tree = unified().use(remarkParse).use(remarkGfm).parse(content);
  const slugger = new GithubSlugger();
  const headings = tree.children.filter(node => node.type === "heading").map(node => ({
    depth: node.depth, title: toString(node), id: slugger.slug(toString(node)),
  }));
  // Keep the heading inside Markdown so the TOC and rendered anchor IDs stay identical.
  return { headings: headings.filter(heading => heading.depth > 1 && heading.depth <= 3) };
}
