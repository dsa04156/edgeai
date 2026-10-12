/** Follow API pagination so selectors also include records beyond the first page. */
export async function registryItems<T>(path: string, request: (path: string) => Promise<Response>, read: (response: Response) => Promise<{ items: T[]; nextOffset: number | null }> = response => response.json()): Promise<T[]> {
  const items: T[] = [];
  let offset = 0;
  for (;;) {
    const page = await read(await request(`${path}?limit=100&offset=${offset}`));
    items.push(...page.items);
    if (page.nextOffset == null) return items;
    if (page.nextOffset <= offset) throw new Error("목록 페이지를 불러오지 못했습니다. 새로고침하세요.");
    offset = page.nextOffset;
  }
}
